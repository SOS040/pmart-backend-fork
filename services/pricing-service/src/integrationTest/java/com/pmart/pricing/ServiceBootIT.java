package com.pmart.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/** 기동 검증: 실제 PostgreSQL(Flyway 공통+서비스 마이그레이션)·Kafka 위에서 컨텍스트가 뜨고 /actuator/health 가 200. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
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

  /** 계약에서 security: [] 인 작업(비회원 둘러보기)은 토큰 없이 인증을 통과한다. 그 밖은 401. */
  @ParameterizedTest
  @CsvSource({
    "/api/v1/prices/E-00000001, false",
    "/api/v1/prices?skus=E-00000001, false",
    "/api/v1/price-versions/E-00000001, true"
  })
  void publicBrowseOperations(String path, boolean authRequired) {
    int status =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + path)
            .exchange((request, response) -> response.getStatusCode().value());
    if (authRequired) {
      assertThat(status).as(path).isEqualTo(401);
    } else {
      assertThat(status).as(path).isNotEqualTo(401);
    }
  }
}
