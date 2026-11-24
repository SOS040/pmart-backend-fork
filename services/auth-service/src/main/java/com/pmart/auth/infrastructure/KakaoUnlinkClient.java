package com.pmart.auth.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * 카카오 앱 연결 끊기(2026-11-21 — 사용자 "권한 동의는 처음 가입할 때만"). 카카오는 앱과 연결된 동안 동의를 기억해 다시 묻지 않으므로, 탈퇴가 끝나면 연결을
 * 끊어 다시 가입할 때 동의 화면이 뜨게 한다. {@code POST {api-base-url}/v1/user/unlink}, Admin 키(서버 전용 비밀값)로 회원번호(ID
 * 토큰 sub)를 지정한다.
 *
 * <p>Admin 키({@code pmart.auth.customer.kakao.admin-key})가 없으면 부르지 않는다. 어떤 실패도 예외 없이 false — 로그는 짧은
 * 까닭 코드만(키·회원번호 금지). 공용 RestClient.Builder 를 쓰지 않는다(사용자 토큰 전달 설정이 밖으로 새지 않게).
 */
@Component
public class KakaoUnlinkClient {

  private static final Logger log = LoggerFactory.getLogger(KakaoUnlinkClient.class);

  private final @Nullable String adminKey;
  private final String apiBaseUrl;
  private final RestClient http;

  public KakaoUnlinkClient(
      @Value("${pmart.auth.customer.kakao.admin-key:}") String adminKey,
      @Value("${pmart.auth.customer.kakao.api-base-url:https://kapi.kakao.com}")
          String apiBaseUrl) {
    this.adminKey = adminKey == null || adminKey.isBlank() ? null : adminKey.strip();
    this.apiBaseUrl = apiBaseUrl.strip().replaceAll("/+$", "");
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  /** 카카오 사용자 정보(동의한 것만). 없는 값은 null. */
  public record Profile(
      @Nullable String email,
      @Nullable Boolean emailVerified,
      @Nullable String nickname,
      @Nullable String imageUrl) {
    @Override
    public String toString() {
      return "KakaoUnlinkClient.Profile[***]";
    }
  }

  /**
   * 카카오 사용자 정보 {@code GET {api-base-url}/v2/user/me}(2026-11-25 — 사용자 "카카오 프로필·이름·이메일 들어가는지"). LAN
   * 의 카카오 ID 토큰에 nickname·picture·email 이 실리지 않아(11-20 로그인 4번 모두 없음) 로그인 때 Admin 키로 그 회원번호의 정보를 직접
   * 읽는다. 이메일은 유효·확인된 것만, 사진은 기본 사진이 아닐 때만(https 주소 — secure_resource). 어떤 실패도 null(로그인은 그대로). 값은 로그
   * 금지.
   */
  public @Nullable Profile profile(String kakaoUserId) {
    if (adminKey == null) {
      return null;
    }
    tools.jackson.databind.JsonNode body;
    try {
      body =
          http.get()
              .uri(
                  apiBaseUrl
                      + "/v2/user/me?target_id_type=user_id&secure_resource=true&target_id={id}",
                  kakaoUserId)
              .header("Authorization", "KakaoAK " + adminKey)
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(tools.jackson.databind.JsonNode.class);
    } catch (HttpStatusCodeException e) {
      log.info("카카오 사용자 정보 생략 reason=http_{}", e.getStatusCode().value());
      return null;
    } catch (RuntimeException e) {
      log.info("카카오 사용자 정보 생략 reason={}", e.getClass().getSimpleName());
      return null;
    }
    if (body == null || !body.isObject()) {
      return null;
    }
    var account = body.path("kakao_account");
    String email = null;
    Boolean verified = null;
    if (account.path("email").isString()
        && account.path("is_email_valid").asBoolean(false)
        && account.path("is_email_verified").asBoolean(false)) {
      email = account.path("email").asString().strip();
      verified = true;
    }
    var profile = account.path("profile");
    String nickname = profile.path("nickname").asString("").strip();
    String image =
        profile.path("is_default_image").asBoolean(false)
            ? ""
            : profile.path("profile_image_url").asString("").strip();
    return new Profile(
        email == null || email.isEmpty() ? null : email,
        email == null || email.isEmpty() ? null : verified,
        nickname.isEmpty() ? null : nickname,
        image.isEmpty() ? null : image);
  }

  public boolean configured() {
    return adminKey != null;
  }

  /** 연결 끊기. 끊었으면 true. */
  public boolean unlink(String kakaoUserId) {
    if (adminKey == null) {
      log.info("카카오 연결 끊기 생략 reason=no_admin_key");
      return false;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("target_id_type", "user_id");
    form.add("target_id", kakaoUserId);
    try {
      http.post()
          .uri(apiBaseUrl + "/v1/user/unlink")
          .header("Authorization", "KakaoAK " + adminKey)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (HttpStatusCodeException e) {
      log.info("카카오 연결 끊기 실패 reason=http_{}", e.getStatusCode().value());
      return false;
    } catch (RuntimeException e) {
      log.info("카카오 연결 끊기 실패 reason={}", e.getClass().getSimpleName());
      return false;
    }
  }
}
