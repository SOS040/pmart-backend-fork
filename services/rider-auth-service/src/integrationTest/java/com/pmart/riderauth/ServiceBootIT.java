package com.pmart.riderauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/** 기동 검증: 실제 PostgreSQL(Flyway) 위에서 컨텍스트가 뜨고 /actuator/health 가 200, 공개 작업은 토큰 없이 401 이 아니다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off", "pmart.rider-auth.signing.ephemeral=true"})
class ServiceBootIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;

  @Test
  void healthIsUpWithoutAuthentication() {
    var body =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/actuator/health")
            .retrieve()
            .body(String.class);
    assertThat(body).contains("\"status\":\"UP\"");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "POST /api/v1/rider-auth/social-logins",
        "GET /api/v1/rider-auth/social-logins/ar-1",
        "POST /api/v1/rider-auth/tokens",
        "GET /api/v1/internal/jwks"
      })
  void publicOperationsDoNotRequireAuthentication(String operation) {
    String[] op = operation.split(" ");
    int status =
        RestClient.create()
            .method(HttpMethod.valueOf(op[0]))
            .uri("http://localhost:" + port + op[1])
            .exchange((request, response) -> response.getStatusCode().value());
    assertThat(status).as(operation).isNotEqualTo(401);
  }

  @Test
  void protectedOperationsRequireToken() {
    int status =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/api/v1/rider-auth/sessions/current")
            .exchange((request, response) -> response.getStatusCode().value());
    assertThat(status).isEqualTo(401);
  }
}
