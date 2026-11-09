package com.pmart.customerassistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * 기동 검증: pgvector PostgreSQL(Flyway V1 — vector·pg_trgm 확장) 위에서 컨텍스트가 뜨고 /actuator/health 가 200.
 * 색인은 하지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
class ServiceBootIT extends PgvectorContainerSupport {

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
}
