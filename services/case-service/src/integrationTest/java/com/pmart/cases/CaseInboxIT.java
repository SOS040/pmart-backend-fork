package com.pmart.cases;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 사건함(0.4.0, 2026-11-16 "사건함 공통 결정"): 도움요청 사건 → RIDER_HELP(재전달 하나), 전달 실패 → DELIVERY_INCIDENT, 위치
 * 끊김 두 번 → 사건 하나 + 재발 이력, 상담원은 보기만, RIDER_OPS 가 집고(남의 것 못 집음) 닫기, 오배송 확인 → MISDELIVERY +
 * MisdeliveryConfirmed, 사건 CaseOpened·CaseAssigned·CaseClosed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.cases.delivery-dispatch-base-url=${dispatch.url}",
      "pmart.test.context=case-inbox"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "dispatch", baseUrlProperties = "dispatch.url")})
class CaseInboxIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("dispatch")
  WireMockServer dispatch;

  final String ops = TestJwt.operator("op-ops-1", List.of("RIDER_OPS"), List.of());
  final String ops2 = TestJwt.operator("op-ops-2", List.of("RIDER_OPS"), List.of());
  final String cs = TestJwt.operator("op-cs-1", List.of("CS_AGENT"), List.of());
  final String admin = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());
  final String md = TestJwt.operator("op-md", List.of("MD"), List.of());

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange(
        (req, res) -> {
          byte[] b = res.getBody().readAllBytes();
          return new Res(
              res.getStatusCode().value(),
              b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
        });
  }

  void publish(String topic, String type, String key, Map<String, Object> payload, String from) {
    kafka
        .send(
            topic,
            key,
            EventEnvelopeFixtures.envelope(type)
                .from(from)
                .aggregate("Delivery", key, 1)
                .payload(payload)
                .buildJson())
        .join();
  }

  static <T> T await(Supplier<T> s, java.util.function.Predicate<T> ok)
      throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    T v = s.get();
    while (!ok.test(v) && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      v = s.get();
    }
    return v;
  }

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  int outbox(String caseId, String type) {
    return count(
        "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = ?", caseId, type);
  }

  @Test
  void helpRequestBecomesCaseThatOpsClaimAndClose() throws Exception {
    String deliveryId = "DLV-CI-" + UUID.randomUUID().toString().substring(0, 8);
    String incidentId = deliveryId + ":REQ-1";
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("incidentId", incidentId);
    p.put("deliveryId", deliveryId);
    p.put("riderId", "RD-1");
    p.put("type", "HELP_REQUEST");
    p.put("category", "ACCIDENT");
    p.put("stage", "PICKED_UP");
    p.put("orderId", "ORD-1");
    p.put("mfcId", "MFC-1");
    publish(
        "delivery.delivery.v1",
        "DeliveryIncidentRaised",
        deliveryId,
        p,
        "delivery-execution-service");
    publish(
        "delivery.delivery.v1",
        "DeliveryIncidentRaised",
        deliveryId,
        p,
        "delivery-execution-service");

    int n =
        await(
            () -> count("SELECT count(*) FROM case_record WHERE subject_ref = ?", deliveryId),
            c -> c > 0);
    Thread.sleep(1000);
    assertThat(count("SELECT count(*) FROM case_record WHERE subject_ref = ?", deliveryId))
        .as("같은 도움요청 두 번 — 사건 하나")
        .isEqualTo(1);
    assertThat(n).isEqualTo(1);

    Res list = send("GET", ops, "/cases?subjectRef=" + deliveryId + "&unassigned=true", null);
    assertThat(list.status()).isEqualTo(200);
    JsonNode item = list.body().path("items").get(0);
    String caseId = item.path("caseId").asString();
    assertThat(item.path("kind").asString()).isEqualTo("RIDER_HELP");
    assertThat(item.path("priority").asString()).as("사고 — 긴급").isEqualTo("P1");
    assertThat(item.path("assigneeGroup").asString()).isEqualTo("DELIVERY_OPS");
    assertThat(item.path("centerId").asString()).isEqualTo("MFC-1");
    assertThat(item.path("slaDueAt").isNull()).isFalse();
    assertThat(item.path("overdue").asBoolean()).isFalse();
    assertThat(item.path("refs").toString()).contains(incidentId, "ORD-1", "RD-1");
    assertThat(outbox(caseId, "CaseOpened")).isEqualTo(1);

    // 상담원은 보기만, 역할 없는 직원은 목록 403
    assertThat(send("GET", cs, "/cases/" + caseId, null).status()).isEqualTo(200);
    assertThat(
            send("POST", cs, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-cs-1"))
                .status())
        .isEqualTo(403);
    assertThat(send("GET", md, "/cases", null).status()).isEqualTo(403);

    // 집기: 자기 자신만, 이미 집은 사건은 다른 직원이 못 집음
    assertThat(
            send("POST", ops, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-2"))
                .status())
        .isEqualTo(403);
    Res claimed =
        send("POST", ops, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-1"));
    assertThat(claimed.status()).as(claimed.body().toString()).isEqualTo(200);
    assertThat(claimed.body().path("state").asString()).isEqualTo("ASSIGNED");
    assertThat(claimed.body().path("assigneeId").asString()).isEqualTo("op-ops-1");
    assertThat(
            send(
                    "POST",
                    ops2,
                    "/cases/" + caseId + "/assignments",
                    Map.of("assigneeId", "op-ops-2"))
                .status())
        .isEqualTo(409);
    assertThat(outbox(caseId, "CaseAssigned")).isEqualTo(1);
    assertThat(
            send("GET", ops, "/cases?subjectRef=" + deliveryId + "&unassigned=true", null)
                .body()
                .path("items"))
        .isEmpty();

    // 닫기: 다른 직원 403, 맡은 직원 200, 이력
    Map<String, Object> close =
        Map.of("resolution", "RESOLVED", "summary", "라이더 통화 — 안전 확인, 다른 라이더로 회수");
    assertThat(send("POST", ops2, "/cases/" + caseId + "/closures", close).status()).isEqualTo(403);
    Res closed = send("POST", ops, "/cases/" + caseId + "/closures", close);
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
    assertThat(closed.body().path("state").asString()).isEqualTo("CLOSED");
    assertThat(closed.body().path("closure").path("resolution").asString()).isEqualTo("RESOLVED");
    assertThat(closed.body().path("history")).hasSize(3);
    assertThat(outbox(caseId, "CaseClosed")).isEqualTo(1);
    assertThat(send("GET", ops, "/cases?subjectRef=" + deliveryId, null).body().path("items"))
        .as("기본 목록은 열린 것만")
        .isEmpty();
    assertThat(
            send("GET", ops, "/cases?subjectRef=" + deliveryId + "&state=CLOSED", null)
                .body()
                .path("items"))
        .hasSize(1);
  }

  @Test
  void deliveryFailureAndLocationStaleAndMisdelivery() throws Exception {
    String deliveryId = "DLV-CF-" + UUID.randomUUID().toString().substring(0, 8);
    publish(
        "delivery.delivery.v1",
        "DeliveryIncidentRaised",
        deliveryId,
        Map.of(
            "incidentId",
            deliveryId + ":F1",
            "deliveryId",
            deliveryId,
            "type",
            "HELP_REQUEST",
            "category",
            "DELIVERY_FAILURE"),
        "delivery-execution-service");
    assertThat(
            await(
                () ->
                    jdbc.queryForList(
                        "SELECT case_type FROM case_record WHERE subject_ref = ?",
                        String.class,
                        deliveryId),
                l -> !l.isEmpty()))
        .containsExactly("DELIVERY_INCIDENT");

    // 위치 끊김 두 번 → 사건 하나 + 재발 이력
    Map<String, Object> stale =
        Map.of(
            "riderId",
            "RD-STALE",
            "deliveryId",
            deliveryId,
            "lastSeenAt",
            Instant.now().minusSeconds(240).toString(),
            "detectedAt",
            Instant.now().toString());
    publish(
        "delivery.tracking.v1",
        "RiderLocationStale",
        "RD-STALE",
        stale,
        "delivery-tracking-service");
    publish(
        "delivery.tracking.v1",
        "RiderLocationStale",
        "RD-STALE",
        stale,
        "delivery-tracking-service");
    await(
        () ->
            count(
                "SELECT count(*) FROM case_history h JOIN case_record c ON c.case_id = h.case_id"
                    + " WHERE c.case_type = 'RIDER_LOCATION_STALE' AND c.subject_ref = ? AND h.action = 'RECURRED'",
                deliveryId),
        c -> c > 0);
    assertThat(
            count(
                "SELECT count(*) FROM case_record WHERE case_type = 'RIDER_LOCATION_STALE' AND subject_ref = ?",
                deliveryId))
        .isEqualTo(1);

    // 오배송 확인 — 상담원이 연다(배정 라이더는 dispatch), 같은 배송 두 번째는 409
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"riderId\":\"RD-9\",\"deliveryId\":\"" + deliveryId + "\"}")));
    Map<String, Object> mis =
        Map.of(
            "kind", "MISDELIVERY",
            "subjectType", "DELIVERY",
            "subjectRef", deliveryId,
            "summary", "고객 통화 — 옆 동으로 전달됨(사진 확인)",
            "evidence",
                List.of(Map.of("system", "delivery-execution", "ref", "PHOTO-1", "kind", "PHOTO")));
    Res created = send("POST", cs, "/cases", mis);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String caseId = created.body().path("caseId").asString();
    assertThat(created.body().path("kind").asString()).isEqualTo("MISDELIVERY");
    assertThat(outbox(caseId, "MisdeliveryConfirmed")).isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'MisdeliveryConfirmed'",
            String.class,
            caseId);
    assertThat(payload).contains("RD-9", "PHOTO-1", "op-cs-1").doesNotContain("옆 동");
    assertThat(send("POST", cs, "/cases", mis).status()).isEqualTo(409);
    assertThat(send("POST", md, "/cases", mis).status()).isEqualTo(403);
    Map<String, Object> other = new LinkedHashMap<>(mis);
    other.put("kind", "RIDER_HELP");
    assertThat(send("POST", admin, "/cases", other).status()).isEqualTo(422);

    // 관리자는 다른 사람에게 배정
    Res a =
        send("POST", admin, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-2"));
    assertThat(a.status()).isEqualTo(200);
    assertThat(a.body().path("assigneeId").asString()).isEqualTo("op-ops-2");
  }
}
