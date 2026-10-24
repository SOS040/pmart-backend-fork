package com.pmart.fulfillment.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.KafkaContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/** 기동 검증: DB 없이(운영과 같게) Kafka 만으로 컨텍스트가 뜨고 /actuator/health 가 200. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
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
}
