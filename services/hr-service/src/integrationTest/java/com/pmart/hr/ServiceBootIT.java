package com.pmart.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/** 기동 검증: 실제 PostgreSQL(Flyway)·Kafka 위에서 뜨고 health 200, 모든 업무 경로는 토큰 없이 401(공개 작업 없음). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off"})
class ServiceBootIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;

  @Test
  void healthIsUp() {
    String body =
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
        "GET /api/v1/hr/employees",
        "GET /api/v1/hr/employees/me",
        "GET /api/v1/hr/employees/OP-1",
        "POST /api/v1/hr/employees",
        "POST /api/v1/hr/employees/OP-1/suspensions"
      })
  void everyOperationRequiresToken(String operation) {
    String[] op = operation.split(" ");
    int status =
        RestClient.create()
            .method(HttpMethod.valueOf(op[0]))
            .uri("http://localhost:" + port + op[1])
            .header("Idempotency-Key", "k-" + System.nanoTime())
            .header("X-Source-System", "it")
            .exchange((q, r) -> r.getStatusCode().value());
    assertThat(status).as(operation).isEqualTo(401);
  }
}
