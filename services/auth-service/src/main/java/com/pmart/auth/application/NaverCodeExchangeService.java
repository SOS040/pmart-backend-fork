package com.pmart.auth.application;

import com.pmart.auth.domain.ProviderPictureRules;
import com.pmart.auth.infrastructure.NaverProfileEmailStore;
import com.pmart.auth.infrastructure.NaverTokenClient;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 네이버 인가 코드 → ID 토큰 서버 교환(2026-11-20 ADR-0078, BR-69 — auth 0.10.0 exchangeNaverCode). 네이버는 client
 * secret 없는 토큰 교환을 거절해(client_secret_post) 서버가 대신 교환하고 id_token 만 돌려준다. 계정·세션은 만들지 않는다.
 *
 * <ul>
 *   <li>설정: Client ID(naver.client-ids 첫 값)·client-secret·callback-url 중 하나라도 없으면 422
 *       CONFIG_REQUIRED.
 *   <li>{@code redirectUri} 는 callback-url 과 글자 그대로 같아야 한다(아니면 400 VALIDATION_FAILED, 네이버를 부르지 않음).
 *   <li>같은 Idempotency-Key 재전송은 {@link RequestIdempotency} 가 처음 받은 id_token 을 다시 준다 — 코드를 두 번 교환하지
 *       않는다(결과는 request_record 에 남는다, ADR-0078 결정 3). 실패는 기록되지 않아 같은 키로 다시 부를 수 있다.
 *   <li>네이버 거절 401 PROVIDER_REJECTED, 응답 없음·5xx 503 PROVIDER_UNAVAILABLE(로그인 흐름과 같은 코드). 실패 횟수
 *       (AUTH_RESTRICTED)에는 세지 않는다 — 기기 ID 를 받지 않는 API 다.
 *   <li>프로필 사진(2026-11-20 BR-70, 계약 0.10.1 — 사용자 "추천대로"): 교환이 되면 네이버 access token 으로 프로필 API 를 한 번
 *       불러 {@code profile_image} 를 {@link ProviderPictureRules#naver}(https·*.pstatic.net)로 걸러 함께
 *       준다. 프로필 호출이 어떻게 실패해도 교환은 성공(사진만 없음). access/refresh token 은 저장·응답하지 않는다 — 멱등 기록에도
 *       id_token·사진 주소만.
 *   <li>이메일(2026-11-21 BR-77): 같은 프로필 응답의 {@code email} 을 id_token {@code sub} 에 10분 묶어 둔다({@link
 *       NaverProfileEmailStore}) — 그 sub 의 검증된 id_token 으로 로그인할 때 CustomerAuthenticated 에 싣고 지운다.
 *       앱에는 주지 않는다(앱이 보내는 이메일은 바꿔치기할 수 있다). 없거나 실패면 이메일 없이 그대로.
 * </ul>
 *
 * <p>코드·verifier·secret·id_token·access token·사진 주소는 로그에 남기지 않는다.
 */
@Service
public class NaverCodeExchangeService {

  private static final Logger log = LoggerFactory.getLogger(NaverCodeExchangeService.class);

  static final String OPERATION = "exchangeNaverCode";

  /**
   * 저장·재생되는 결과(JSON 왕복). 0.10.0 때 기록은 profileImageUrl 이 없어 null 로 읽힌다.
   *
   * @param idToken 네이버 OIDC id_token
   * @param profileImageUrl 걸러진 네이버 프로필 사진 주소(없을 수 있음)
   */
  public record Exchanged(String idToken, @Nullable String profileImageUrl) {
    @Override
    public String toString() {
      return "Exchanged[***]";
    }
  }

  private final CustomerProperties props;
  private final NaverTokenClient naver;
  private final RequestIdempotency idempotency;
  private final @Nullable NaverProfileEmailStore emails;
  private final Clock clock;

  /** 교환 때 받은 이메일을 묶어 두는 시간(그 안에 같은 id_token 으로 로그인한다). */
  static final Duration EMAIL_HOLD = Duration.ofMinutes(10);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** 단위 시험용 — 이메일을 묶어 두지 않는다. */
  public NaverCodeExchangeService(
      CustomerProperties props, NaverTokenClient naver, @Nullable RequestIdempotency idempotency) {
    this(props, naver, idempotency, null, Clock.systemUTC());
  }

  @Autowired
  public NaverCodeExchangeService(
      CustomerProperties props,
      NaverTokenClient naver,
      @Nullable RequestIdempotency idempotency,
      @Nullable NaverProfileEmailStore emails,
      Clock clock) {
    this.props = props;
    this.naver = naver;
    this.idempotency = idempotency;
    this.emails = emails;
    this.clock = clock;
  }

  public Exchanged exchange(
      String requestId,
      String sourceSystem,
      String authorizationCode,
      String codeVerifier,
      String redirectUri,
      @Nullable String state) {
    return exchange(
        requestId, sourceSystem, authorizationCode, codeVerifier, redirectUri, state, false);
  }

  /**
   * @param withdrawal 탈퇴 재인증(purpose WITHDRAWAL, 2026-11-20 BR-74 (C)) — 교환 뒤 네이버 앱 연결을 끊는다. 로그인은
   *     설정 {@code unlink-after-exchange}(LAN 시험용 (A))가 켜졌을 때만 끊는다
   */
  public Exchanged exchange(
      String requestId,
      String sourceSystem,
      String authorizationCode,
      String codeVerifier,
      String redirectUri,
      @Nullable String state,
      boolean withdrawal) {
    CustomerProperties.NaverExchange cfg = props.naverExchange();
    if (cfg.clientId() == null) {
      throw PmartException.configRequired("pmart.auth.customer.naver.client-ids");
    }
    if (cfg.clientSecret() == null) {
      throw PmartException.configRequired("pmart.auth.customer.naver.client-secret");
    }
    if (cfg.callbackUrl() == null) {
      throw PmartException.configRequired("pmart.auth.customer.naver.callback-url");
    }
    if (!cfg.callbackUrl().equals(redirectUri)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "redirectUri 가 이 앱의 네이버 콜백 주소와 다릅니다.");
    }
    // 본문 비교: 코드·verifier·redirectUri(해시만 저장된다)
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("authorizationCode", authorizationCode);
    request.put("codeVerifier", codeVerifier);
    request.put("redirectUri", redirectUri);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, OPERATION, RequestIdempotency.ANONYMOUS),
            request,
            Exchanged.class,
            () -> {
              NaverTokenClient.Tokens tokens =
                  call(cfg, authorizationCode, codeVerifier, redirectUri, state);
              NaverTokenClient.Profile profile = profile(tokens.accessToken());
              Exchanged out = new Exchanged(tokens.idToken(), pickImage(profile.image()));
              holdEmail(tokens.idToken(), profile.email());
              // 프로필을 읽은 뒤 끊는다(끊으면 access token 이 무효)
              if ((withdrawal || cfg.unlinkAfterExchange()) && tokens.accessToken() != null) {
                boolean ok =
                    naver.unlink(
                        cfg.tokenBaseUrl(),
                        cfg.clientId(),
                        cfg.clientSecret(),
                        tokens.accessToken());
                log.info("네이버 연결 끊기 purpose={} ok={}", withdrawal ? "WITHDRAWAL" : "LOGIN", ok);
              }
              return out;
            })
        .value();
  }

  /** 프로필 사진만(시험·BR-70). */
  @Nullable String profileImage(
      CustomerProperties.NaverExchange cfg, @Nullable String accessToken) {
    return pickImage(profile(accessToken).image());
  }

  /** 프로필 API 한 번 — 어떤 실패도 NONE(교환은 그대로). access token 은 여기서 쓰고 버린다. */
  private NaverTokenClient.Profile profile(@Nullable String accessToken) {
    if (accessToken == null) {
      log.info("네이버 프로필 생략 reason=missing_access_token");
      return NaverTokenClient.Profile.NONE;
    }
    try {
      return naver.profile(props.naverExchange().profileBaseUrl(), accessToken);
    } catch (RuntimeException e) {
      log.info("네이버 프로필 생략 reason={}", e.getClass().getSimpleName());
      return NaverTokenClient.Profile.NONE;
    }
  }

  /** 이메일을 id_token sub 에 묶어 둔다. 실패는 경고만(로그인은 이메일 없이). 이메일·sub 는 로그 금지. */
  private void holdEmail(String idToken, @Nullable String email) {
    if (emails == null || email == null) {
      return;
    }
    String e = email.strip();
    if (e.length() > 320 || e.indexOf('@') < 1 || e.chars().anyMatch(Character::isWhitespace)) {
      log.info("네이버 프로필 이메일 생략 reason=invalid_email");
      return;
    }
    String sub = subjectOf(idToken);
    if (sub == null) {
      log.info("네이버 프로필 이메일 생략 reason=missing_sub");
      return;
    }
    try {
      emails.save(sub, e, clock.instant().plus(EMAIL_HOLD));
    } catch (RuntimeException ex) {
      log.warn("네이버 프로필 이메일 보관 실패 reason={}", ex.getClass().getSimpleName());
    }
  }

  /**
   * id_token 의 sub(서명 검증 없이 키로만 쓴다 — 로그인 때 검증된 같은 sub 로만 읽히므로 위조 토큰은 남의 계정에 닿지 않는다. 이 토큰은 방금 네이버 토큰
   * 엔드포인트가 직접 준 것이다).
   */
  static @Nullable String subjectOf(String idToken) {
    String[] parts = idToken.split("[.]");
    if (parts.length < 2) {
      return null;
    }
    try {
      JsonNode claims =
          JSON.readTree(
              new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
      String sub = claims.path("sub").asString("");
      return sub.isBlank() ? null : sub;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static @Nullable String pickImage(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String picked = ProviderPictureRules.naver(raw);
    if (picked == null) {
      log.info("네이버 프로필 사진 생략 reason=not_allowed_url");
    }
    return picked;
  }

  private NaverTokenClient.Tokens call(
      CustomerProperties.NaverExchange cfg,
      String code,
      String verifier,
      String redirectUri,
      @Nullable String state) {
    try {
      return naver.exchange(
          cfg.tokenBaseUrl(),
          new NaverTokenClient.Request(
              cfg.clientId(), cfg.clientSecret(), code, verifier, redirectUri, state));
    } catch (NaverTokenClient.Rejected e) {
      throw new PmartException(
          "PROVIDER_REJECTED",
          HttpStatus.UNAUTHORIZED,
          "네이버 인증 결과를 확인할 수 없습니다. 다시 로그인하세요.",
          false,
          List.of());
    } catch (NaverTokenClient.Unavailable e) {
      throw new PmartException(
          "PROVIDER_UNAVAILABLE",
          HttpStatus.SERVICE_UNAVAILABLE,
          "네이버 로그인 확인이 잠시 안 됩니다. 잠시 뒤 다시 시도하세요.",
          true,
          List.of());
    }
  }
}
