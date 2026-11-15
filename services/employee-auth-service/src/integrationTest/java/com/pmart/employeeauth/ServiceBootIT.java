package com.pmart.employeeauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/**
 * 기동 검증: 실제 PostgreSQL(Flyway) 위에서 컨텍스트가 뜨고 /actuator/health 가 200, 공개 작업은 토큰 없이 401 이 아니다, 보호 작업은
 * 401, Keycloak 설정이 없으면 로그인 시작이 CONFIG_REQUIRED(422), 시험 교환 경로는 설정으로 켜지 않으면 없다(404).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off"})
class ServiceBootIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;

  int status(String method, String path) {
    return RestClient.create()
        .method(HttpMethod.valueOf(method))
        .uri("http://localhost:" + port + path)
        .header("Idempotency-Key", "k-" + System.nanoTime())
        .header("X-Source-System", "it")
        .header("X-Device-Id", "d1")
        .exchange((request, response) -> response.getStatusCode().value());
  }

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
        "GET /api/v1/employee-auth/logins",
        "GET /api/v1/employee-auth/logins/callback",
        "POST /api/v1/employee-auth/tokens",
        "POST /api/v1/employee-auth/tokens/refresh",
        "GET /api/v1/internal/jwks"
      })
  void publicOperationsDoNotRequireAuthentication(String operation) {
    String[] op = operation.split(" ");
    assertThat(status(op[0], op[1])).as(operation).isNotEqualTo(401);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "GET /api/v1/employee-auth/sessions/current",
        "POST /api/v1/employee-auth/logouts",
        "GET /api/v1/employee-auth/employees/OP-1",
        "GET /api/v1/employee-auth/roles"
      })
  void protectedOperationsRequireToken(String operation) {
    String[] op = operation.split(" ");
    assertThat(status(op[0], op[1])).as(operation).isEqualTo(401);
  }

  @Test
  void loginWithoutKeycloakSettingsIsConfigRequired() {
    assertThat(
            status(
                "GET",
                "/api/v1/employee-auth/logins?app=mfc&deviceId=d1&codeChallenge=" + "a".repeat(43)))
        .isEqualTo(422);
  }

  @Test
  void devTokenExchangeDoesNotExistUnlessEnabled() {
    assertThat(status("POST", "/api/v1/internal/dev-token-exchange")).isEqualTo(404);
    // 로컬 개발 발급도 켜지 않으면 없다(local 프로필만 켠다)
    assertThat(status("POST", "/api/v1/internal/dev-tokens")).isEqualTo(404);
  }
}
