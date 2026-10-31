package com.pmart.mfc.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 단위 작업 중지(S3-a): 작업 지시가 없는 주문은 전량 STOPPED(notStartedLines) + WorkStopResult 한 번. 같은 중지 요청 ID 는
 * 기존 판정(다른 키여도), 다른 수량이면 409. 서비스 토큰만.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class WorkStopIT extends PostgresKafkaContainerSupport {

  static final String SVC = TestJwt.service("order-orchestration-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  Res stop(String token, String key, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/internal/stop-requests")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "order-orchestration-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  @Test
  void stopWithoutWorkOrderStopsEverything() {
    String orderId = "ORD-" + UUID.randomUUID();
    String stopId = "STOP-" + UUID.randomUUID();
    Map<String, Object> body =
        Map.of(
            "stopRequestId",
            stopId,
            "orderId",
            orderId,
            "claimId",
            "CLM-1",
            "scope",
            "FULL",
            "lines",
            List.of(
                Map.of("orderLineId", orderId + "-L1", "qty", 2),
                Map.of("orderLineId", orderId + "-L2", "qty", 1)));

    // 고객 토큰 403
    assertThat(stop(TestJwt.customer("CU-1"), UUID.randomUUID().toString(), body).status())
        .isEqualTo(403);

    Res r = stop(SVC, "SAGA-1:STOP_WORK", body);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("state").asString()).isEqualTo("RESOLVED");
    assertThat(r.body().path("orderId").asString()).isEqualTo(orderId);
    assertThat(r.body().path("packages")).isEmpty();
    assertThat(r.body().path("notStartedLines")).hasSize(2);
    assertThat(r.body().path("notStartedLines").path(0).path("qty").asInt()).isEqualTo(2);

    // 같은 키 재생, 다른 키·같은 중지 요청 → 기존 판정, 사건 한 번
    assertThat(stop(SVC, "SAGA-1:STOP_WORK", body).status()).isEqualTo(200);
    Res again = stop(SVC, UUID.randomUUID().toString(), body);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("resolvedAt").asString())
        .isEqualTo(r.body().path("resolvedAt").asString());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'WorkStopResult' AND aggregateid = ?",
                Integer.class,
                orderId))
        .isEqualTo(1);
    Map<String, Object> ev =
        jdbc.queryForMap(
            "SELECT topic, payload::text AS payload FROM outbox_event"
                + " WHERE type = 'WorkStopResult' AND aggregateid = ?",
            orderId);
    assertThat(ev.get("topic")).isEqualTo("mfc.fulfillment.v1");
    JsonNode p = mapper.readTree((String) ev.get("payload")).path("payload");
    assertThat(p.path("stopRequestId").asString()).isEqualTo(stopId);
    assertThat(p.path("claimId").asString()).isEqualTo("CLM-1");
    assertThat(p.path("perPackage")).isEmpty();
    assertThat(p.path("notStartedLines")).hasSize(2);

    // 같은 중지 요청 ID 에 다른 수량 → 409
    Map<String, Object> other =
        Map.of(
            "stopRequestId",
            stopId,
            "orderId",
            orderId,
            "scope",
            "FULL",
            "lines",
            List.of(Map.of("orderLineId", orderId + "-L1", "qty", 1)));
    Res conflict = stop(SVC, UUID.randomUUID().toString(), other);
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body().path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");

    // 재조회
    Res got = get(SVC, "/internal/stop-requests/" + stopId);
    assertThat(got.status()).isEqualTo(200);
    assertThat(got.body().path("state").asString()).isEqualTo("RESOLVED");
    assertThat(get(SVC, "/internal/stop-requests/STOP-NOPE").status()).isEqualTo(404);
  }
}
