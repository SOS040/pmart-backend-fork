package com.pmart.riderauth.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.riderauth.infrastructure.NaverTokenClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 네이버 인가 코드 → ID 토큰 서버 교환(ADR-0078, 2026-11-20 BR-69). 네이버 토큰 엔드포인트는 client secret 없는 교환을 거절하므로
 * (client_secret_post) 앱 대신 서버가 secret 으로 교환하고 <b>id_token 만</b> 돌려준다. 계정·세션은 만들지 않는다 — 앱은 받은
 * id_token 으로 지금처럼 로그인({@link RiderLoginService})한다.
 *
 * <ul>
 *   <li>{@code redirectUri} 는 설정의 라이더 앱 콜백 중계 주소와 정확히 같아야 한다(400 VALIDATION_FAILED).
 *   <li>같은 Idempotency-Key·같은 본문 재전송은 처음 받은 id_token 을 다시 준다(코드를 두 번 교환하지 않음). 결과는 {@code
 *       request_record} 에 남는다(ADR-0078 결정 3). 같은 키가 동시에 오면 뒤 요청은 앞 교환이 끝날 때까지 기다린다 — 그래서 교환은 기록
 *       트랜잭션 안에서 하고, 네이버 호출 시간은 연결·응답 제한(기본 2초·5초)으로 묶는다.
 *   <li>네이버 거절 401 PROVIDER_REJECTED(네이버 오류 코드만 로그), 응답 없음·5xx 503 PROVIDER_UNAVAILABLE. 실패는 기록되지
 *       않는다(같은 키로 다시 보내면 다시 교환 — 같은 코드면 네이버가 거절한다).
 *   <li>로그인 실패 횟수(기기별 제한)에는 넣지 않는다 — 이 API 는 기기·계정을 모른다.
 * </ul>
 */
@Service
public class NaverCodeExchangeService {

  static final String OPERATION = "exchangeNaverCode";

  private static final Logger log = LoggerFactory.getLogger(NaverCodeExchangeService.class);

  /** 기록·재생되는 결과. */
  public record Exchanged(String idToken) {
    @Override
    public String toString() {
      return "Exchanged[idToken=****]";
    }
  }

  private final NaverExchangeProperties props;
  private final NaverTokenClient naver;
  private final RequestIdempotency idempotency;

  public NaverCodeExchangeService(
      NaverExchangeProperties props, NaverTokenClient naver, RequestIdempotency idempotency) {
    this.props = props;
    this.naver = naver;
    this.idempotency = idempotency;
  }

  public String exchange(
      String idempotencyKey,
      String sourceSystem,
      String authorizationCode,
      String codeVerifier,
      String redirectUri,
      @Nullable String state) {
    String clientId = props.clientId();
    if (clientId == null) {
      throw PmartException.configRequired("pmart.rider-auth.naver.client-ids");
    }
    String secret = props.clientSecret();
    if (secret == null) {
      throw PmartException.configRequired("pmart.rider-auth.naver.client-secret");
    }
    String callback = props.callbackUrl();
    if (callback == null) {
      throw PmartException.configRequired("pmart.rider-auth.naver.callback-url");
    }
    if (!callback.equals(redirectUri)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "redirectUri 가 라이더 앱 네이버 콜백 주소와 다릅니다.",
          List.of(new PmartException.Detail("redirectUri", "설정된 콜백 주소와 같아야 합니다")));
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("authorizationCode", authorizationCode);
    request.put("codeVerifier", codeVerifier);
    request.put("redirectUri", redirectUri);
    request.put("state", state);
    RequestIdempotency.Outcome<Exchanged> outcome =
        idempotency.execute(
            new RequestKey(sourceSystem, idempotencyKey, OPERATION, RequestIdempotency.ANONYMOUS),
            request,
            Exchanged.class,
            () ->
                new Exchanged(
                    call(
                        new NaverTokenClient.Exchange(
                            clientId,
                            secret,
                            authorizationCode,
                            codeVerifier,
                            redirectUri,
                            state))));
    if (outcome.replayed()) {
      log.info("네이버 코드 교환 재전송 — 처음 결과를 돌려줌");
    }
    return outcome.value().idToken();
  }

  private String call(NaverTokenClient.Exchange e) {
    try {
      String idToken = naver.exchange(e);
      log.info("네이버 코드 교환 성공");
      return idToken;
    } catch (NaverTokenClient.Rejected ex) {
      log.warn("네이버 코드 교환 거절(PROVIDER_REJECTED) error={}", ex.errorCode());
      throw new PmartException(
          "PROVIDER_REJECTED",
          HttpStatus.UNAUTHORIZED,
          "네이버 인가 코드를 확인할 수 없습니다. 다시 로그인하세요.",
          false,
          List.of());
    } catch (NaverTokenClient.Unavailable ex) {
      log.warn("네이버 코드 교환 불가(PROVIDER_UNAVAILABLE): {}", ex.getMessage());
      throw new PmartException(
          "PROVIDER_UNAVAILABLE",
          HttpStatus.SERVICE_UNAVAILABLE,
          "네이버 인증 확인이 잠시 안 됩니다. 잠시 뒤 다시 시도하세요.",
          true,
          List.of());
    }
  }
}
