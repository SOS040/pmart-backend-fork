package com.pmart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * 내부 경로·JWKS(ADR-0011·0026): JWKS 는 공개키만, 내부 API 는 서비스 토큰 없이 들어온다(서비스 간 인증 없음). 서비스 토큰 발급 경로는 없다.
 * 위조 토큰은 내부 경로에서도 401(토큰이 붙으면 검증한다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test"
    })
class InternalAccessIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private final RestClient http =
      RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();

  @Test
  void jwksIsPublicAndHasNoPrivateParts() {
    @SuppressWarnings("unchecked")
    Map<String, Object> jwks =
        http.get().uri(url("/api/v1/internal/jwks")).retrieve().body(Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
    assertThat(keys).hasSize(1);
    assertThat(keys.getFirst()).containsKeys("kid", "n", "e").doesNotContainKeys("d", "p", "q");
    assertThat(keys.getFirst()).containsEntry("alg", "RS256").containsEntry("use", "sig");
  }

  @Test
  void internalApiNeedsNoServiceToken() {
    // 보안을 통과하면 아직 구현 전인 스텁의 501
    assertThat(internalCall(null).getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
  }

  @Test
  void serviceTokenEndpointIsGone() {
    ResponseEntity<Void> res =
        http.post()
            .uri(url("/api/v1/internal/service-tokens"))
            .headers(h -> h.setBasicAuth("order-service", "pmart-local"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials")
            .retrieve()
            .toBodilessEntity();
    assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
  }

  @Test
  void forgedTokenIsRejected() {
    // 다른 키로 서명된 토큰(위조)은 401
    assertThat(
            internalCall("eyJhbGciOiJSUzI1NiIsImtpZCI6IngifQ.eyJzdWIiOiJ4In0.c2ln").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private ResponseEntity<Void> internalCall(String token) {
    var spec =
        http.get()
            .uri(url("/api/v1/internal/principals/op-1/grants"))
            .accept(MediaType.APPLICATION_JSON);
    if (token != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return spec.retrieve().toBodilessEntity();
  }
}
