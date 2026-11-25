package com.pmart.customer.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
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
 * 탈퇴 때 구글 연결 끊기(2026-11-25 BR-105 — 사용자 "구글도 소셜(서버)이 끊게" "1번"). 앱이 탈퇴 재인증 직후 받은 구글 OAuth 접근 토큰으로
 * {@code POST https://oauth2.googleapis.com/revoke}(form token). 클라이언트 비밀값 없음. 토큰은 저장·로그하지 않고 한 번
 * 쓰고 버린다. 어떤 실패도 예외 없이 false — 탈퇴는 그대로(앱의 기기 disconnect 가 두 번째 겹). 공용 RestClient.Builder 를 쓰지
 * 않는다(사용자 토큰 전파 없이).
 */
@Component
public class GoogleRevokeClient {

  private static final Logger log = LoggerFactory.getLogger(GoogleRevokeClient.class);

  private final String revokeUrl;
  private final RestClient http;

  public GoogleRevokeClient(
      @Value("${pmart.customer.withdrawal.google-revoke-url:https://oauth2.googleapis.com/revoke}")
          String revokeUrl) {
    this.revokeUrl = revokeUrl;
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

  /** 끊었으면 true. 결과는 로그에 성공/실패·HTTP 코드만. */
  public boolean revoke(String accessToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("token", accessToken);
    try {
      http.post()
          .uri(revokeUrl)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
      log.info("구글 연결 끊기 ok=true");
      return true;
    } catch (HttpStatusCodeException e) {
      log.info("구글 연결 끊기 ok=false reason=http_{}", e.getStatusCode().value());
      return false;
    } catch (RuntimeException e) {
      log.info("구글 연결 끊기 ok=false reason={}", e.getClass().getSimpleName());
      return false;
    }
  }
}
