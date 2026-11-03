package com.pmart.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Eureka 서버(ADR-0032): Basic 인증, 등록 → 조회, 상태 확인·지표는 인증 없이. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EurekaServerIT {

  @LocalServerPort int port;

  private RestClient client(boolean auth) {
    RestClient.Builder b =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {});
    if (auth) {
      b.defaultHeaders(h -> h.setBasicAuth("pmart", "it-eureka-password"));
    }
    return b.build();
  }

  private HttpStatusCode status(RestClient c, String path) {
    return c.get().uri(path).retrieve().toBodilessEntity().getStatusCode();
  }

  @Test
  void registryRequiresBasicAuth() {
    assertThat(status(client(false), "/eureka/apps").value()).isEqualTo(401);
    assertThat(status(client(true), "/eureka/apps").value()).isEqualTo(200);
    assertThat(status(client(false), "/").value()).isEqualTo(401); // 대시보드
  }

  @Test
  void healthAndMetricsAreOpen() {
    assertThat(status(client(false), "/actuator/health").value()).isEqualTo(200);
    assertThat(status(client(false), "/actuator/prometheus").value()).isEqualTo(200);
    assertThat(status(client(false), "/actuator/env").value()).isIn(401, 403);
  }

  @Test
  void registeredInstanceIsListedWithHostIpAndPort() {
    String instance =
        """
        {"instance":{"instanceId":"pricing-service:192.168.0.34:8107","hostName":"192.168.0.34",
        "app":"PRICING-SERVICE","ipAddr":"192.168.0.34","status":"UP",
        "port":{"$":8107,"@enabled":"true"},"securePort":{"$":443,"@enabled":"false"},
        "dataCenterInfo":{"@class":"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo","name":"MyOwn"}}}
        """;
    HttpStatusCode registered =
        client(true)
            .post()
            .uri("/eureka/apps/PRICING-SERVICE")
            .contentType(MediaType.APPLICATION_JSON)
            .body(instance)
            .retrieve()
            .toBodilessEntity()
            .getStatusCode();
    assertThat(registered.value()).isEqualTo(204);

    String apps =
        client(true)
            .get()
            .uri("/eureka/apps/PRICING-SERVICE")
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .retrieve()
            .body(String.class);
    assertThat(apps).contains("192.168.0.34").contains("8107");
  }
}
