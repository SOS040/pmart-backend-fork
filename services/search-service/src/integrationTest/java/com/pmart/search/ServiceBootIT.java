package com.pmart.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.KafkaContainerSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/** 기동 검증: DB 없이(운영과 같게) Kafka 만으로 컨텍스트가 뜨고 /actuator/health 가 200. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.search.elasticsearch-url=http://localhost:1"
    })
class ServiceBootIT extends KafkaContainerSupport {

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

  /** 계약에서 security: [] 인 작업은 토큰 없이 인증을 통과한다(구현 상태에 따라 2xx/4xx/501 이지만 401 은 아니다). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "GET /api/v1/search/products?q=milk",
        "GET /api/v1/search/suggestions?q=mi",
        "GET /api/v1/search/filters?q=milk"
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
}
