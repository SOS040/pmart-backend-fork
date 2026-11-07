package com.pmart.mfc.fulfillment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 슬라이스 6b 점원 단말 흐름: 배정 투영(AssignmentPublished) → 시작(배정 동기 조회, 첫 PICK = PreparationStarted) → 피킹
 * 실적(재고 PICK) → PICK 완료·PACK 준비 → 포장 밀봉(재고 PACK) → 출고 READY·ShipmentReady. 결품 보고 BLOCKED, 재고 거절, 피킹
 * 뒤 중지 PENDING. 재고·배정 정본은 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.fulfillment.mfc-inventory-base-url=${inv.url}",
      "pmart.fulfillment.mfc-capacity-base-url=${cap.url}",
      "pmart.fulfillment.ready-sweep-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "inv", baseUrlProperties = "inv.url"),
  @ConfigureWireMock(name = "cap", baseUrlProperties = "cap.url")
})
class TaskFlowIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-T6";
  static final String SVC = TestJwt.service("order-orchestration-service");
  static final String W1 = TestJwt.operator("w1", List.of("MFC_WORKER"), List.of(MFC));
  static final String W2 = TestJwt.operator("w2", List.of("MFC_WORKER"), List.of(MFC));
  static final String VIEWER = TestJwt.operator("v1", List.of(), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("inv")
  WireMockServer inv;

  @InjectWireMock("cap")
  WireMockServer cap;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    inv.resetAll();
    cap.resetAll();
  }

  // ---------- 도우미 ----------

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(new ObjectMapper().writeValueAsString(body));
  }

  Res call(String method, String token, String path, @Nullable String key, @Nullable Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  if (key != null) {
                    h.set("Idempotency-Key", key);
                    h.set("X-Source-System", "mfc-pda");
                  }
                });
    var e =
        (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 접수된 작업 지시(PICK READY·PACK WAITING). 줄 L1: 2개(두 LOT), L2: 1개. */
  String accept(String o) {
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        "RSV-" + o,
                        "state",
                        "CONFIRMED",
                        "parts",
                        List.of(
                            Map.of(
                                "partId",
                                "P1",
                                "sku",
                                "E-00000001",
                                "qty",
                                2,
                                "state",
                                "CONFIRMED"),
                            Map.of(
                                "partId",
                                "P2",
                                "sku",
                                "E-00000002",
                                "qty",
                                1,
                                "state",
                                "CONFIRMED"))))));
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", o);
    b.put("workVersion", 1);
    b.put("mfcId", MFC);
    b.put("reservationId", "RSV-" + o);
    b.put("deliveryMode", "SINGLE");
    b.put(
        "shipments",
        List.of(
            Map.of(
                "shipmentId",
                o + "-S1",
                "lines",
                List.of(
                    Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 2, "productName", "두부"),
                    Map.of("orderLineId", "L2", "sku", "E-00000002", "qty", 1)))));
    Res r = call("POST", SVC, "/internal/work-orders", "c-" + o, b);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return o + "-S1";
  }

  /** 피킹 지시(inventory pick-plan) — picked·packed 는 시험 단계에 맞춰. */
  void plan(
      String o, int pickedA, int pickedB, int packedA, int packedB, int pickedL2, int packedL2) {
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/pick-plan"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        "RSV-" + o,
                        "state",
                        "CONFIRMED",
                        "parts",
                        List.of(
                            part(
                                "L1",
                                "E-00000001",
                                "LOT-A",
                                "2026-11-20",
                                1,
                                pickedA,
                                packedA,
                                "A-S1-01-1-01"),
                            part(
                                "L1",
                                "E-00000001",
                                "LOT-B",
                                "2026-12-01",
                                1,
                                pickedB,
                                packedB,
                                "A-S1-02-1-01"),
                            part(
                                "L2",
                                "E-00000002",
                                "LOT-C",
                                null,
                                1,
                                pickedL2,
                                packedL2,
                                "C-S1-01-1-01"))))));
  }

  static Map<String, Object> part(
      String line,
      String sku,
      String lot,
      @Nullable String exp,
      int qty,
      int picked,
      int packed,
      String loc) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orderLineId", line);
    m.put("sku", sku);
    m.put("lotId", lot);
    m.put("expiryDate", exp);
    m.put("state", "CONFIRMED");
    m.put("qty", qty);
    m.put("pickedQty", picked);
    m.put("packedQty", packed);
    m.put("locations", List.of(Map.of("locationCode", loc, "qty", 5)));
    return m;
  }

  void assigned(String taskId, String worker, int version) {
    String json =
        EventEnvelopeFixtures.envelope("AssignmentPublished")
            .from("mfc-capacity-service")
            .aggregate("AssignmentPlan", "MANUAL:" + taskId, version)
            .payload(
                Map.of(
                    "planVersion",
                    version,
                    "mfcId",
                    MFC,
                    "assignments",
                    List.of(
                        Map.of(
                            "taskId", taskId, "workerId", worker, "assignmentVersion", version))))
            .buildJson();
    kafka.send("mfc.capacity.v1", "MANUAL:" + taskId, json).join();
    cap.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/assignments"))
            .withQueryParam("taskId", equalTo(taskId))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "taskId",
                                taskId,
                                "workerId",
                                worker,
                                "assignmentVersion",
                                version,
                                "state",
                                "ACTIVE"))))));
    await(
        "SELECT COUNT(*) FROM task WHERE task_id = '"
            + taskId
            + "' AND assignment_version = "
            + version);
  }

  void await(String countSql) {
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until) {
      Integer n = jdbc.queryForObject(countSql, Integer.class);
      if (n != null && n > 0) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("시간 안에 반영 안 됨: " + countSql);
  }

  void inventoryCommits(String type, int status, Object body) {
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo(type)))
            .willReturn(json(status, body)));
  }

  Res start(String token, String taskId, String worker, int version) {
    return call(
        "POST",
        token,
        "/tasks/" + taskId + "/starts",
        UUID.randomUUID().toString(),
        Map.of("workerId", worker, "assignmentVersion", version, "deviceId", "PDA-1"));
  }

  int outbox(String type, String aggregateId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
        Integer.class,
        type,
        aggregateId);
  }

  // ---------- 시험 ----------

  @Test
  void pickThenPackMakesShipmentReady() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    String pack = s + "-PACK";

    // 배정 전: 시작 못 한다(담당 없음), 감독자가 배정 → ASSIGNED
    cap.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/assignments"))
            .willReturn(json(200, Map.of("items", List.of()))));
    Res noAssignee = start(W1, pick, "w1", 0);
    assertThat(noAssignee.status()).isEqualTo(409);
    assertThat(noAssignee.body().path("code").asString()).isEqualTo("NOT_ASSIGNEE");
    assigned(pick, "w1", 1);
    JsonNode mine = call("GET", W1, "/tasks?mfcId=" + MFC + "&assignee=me", null, null).body();
    assertThat(mine.path("items").findValuesAsString("taskId")).contains(pick);

    // 다른 작업자 토큰으로 w1 시작 → 403, w2 는 담당 아님 → 409
    assertThat(start(W2, pick, "w1", 1).status()).isEqualTo(403);
    assertThat(start(W2, pick, "w2", 1).body().path("code").asString()).isEqualTo("NOT_ASSIGNEE");

    // w1 시작 → IN_PROGRESS, 준비 시작 사건 한 번
    String key = UUID.randomUUID().toString();
    Res started =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/starts",
            key,
            Map.of("workerId", "w1", "assignmentVersion", 1));
    assertThat(started.status()).as(started.body().toString()).isEqualTo(201);
    Res replay =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/starts",
            key,
            Map.of("workerId", "w1", "assignmentVersion", 1));
    assertThat(replay.status()).isEqualTo(201);
    assertThat(start(W1, pick, "w1", 1).body().path("code").asString())
        .isEqualTo("ALREADY_STARTED");
    assertThat(outbox("PreparationStarted", o)).isEqualTo(1);
    assertThat(
            call("GET", W1, "/tasks/" + pick + "/starts/" + key, null, null)
                .body()
                .path("outcome")
                .asString())
        .isEqualTo("STARTED");

    // 상세: LOT 별 지시(소비기한·위치)
    plan(o, 0, 0, 0, 0, 0, 0);
    JsonNode detail = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(detail.path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(detail.path("activeStart").path("workerId").asString()).isEqualTo("w1");
    JsonNode l1 = detail.path("lines").path(0);
    assertThat(l1.path("picks")).hasSize(2);
    assertThat(l1.path("lotId").asString()).isEqualTo("LOT-A");
    assertThat(l1.path("locationId").asString()).isEqualTo("A-S1-01-1-01");

    // 재고가 거절한 실적 → REJECTED, 수량 변화 없음
    inventoryCommits("PICK", 422, Map.of("code", "LOT_MISMATCH", "detail", "예약 LOT 이 아닙니다"));
    Res rejected =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            Map.of(
                "executionType",
                "PICK",
                "workerId",
                "w1",
                "fromLocation",
                "A-S1-09-1-01",
                "toLocation",
                "PAK-A-01",
                "lines",
                List.of(
                    Map.of(
                        "orderLineId",
                        "L1",
                        "sku",
                        "E-00000001",
                        "lotId",
                        "LOT-Z",
                        "pickedQty",
                        1))));
    assertThat(rejected.status()).isEqualTo(202);
    assertThat(rejected.body().path("status").asString()).isEqualTo("REJECTED");

    // 반영된 실적: L1 2개(두 선반은 두 번), L2 1개 → PICK 완료, PACK 준비
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));
    for (var line :
        List.of(
            List.of("L1", "E-00000001", "LOT-A", "A-S1-01-1-01"),
            List.of("L1", "E-00000001", "LOT-B", "A-S1-02-1-01"),
            List.of("L2", "E-00000002", "LOT-C", "C-S1-01-1-01"))) {
      Res ok =
          call(
              "POST",
              W1,
              "/tasks/" + pick + "/executions",
              UUID.randomUUID().toString(),
              Map.of(
                  "executionType",
                  "PICK",
                  "workerId",
                  "w1",
                  "fromLocation",
                  line.get(3),
                  "toLocation",
                  "PAK-A-01",
                  "lines",
                  List.of(
                      Map.of(
                          "orderLineId",
                          line.get(0),
                          "sku",
                          line.get(1),
                          "lotId",
                          line.get(2),
                          "pickedQty",
                          1))));
      assertThat(ok.status()).as(ok.body().toString()).isEqualTo(202);
      assertThat(ok.body().path("status").asString()).isEqualTo("APPLIED");
    }
    // 재고 호출에 예약·주문 줄이 실린다
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.reservationId", equalTo("RSV-" + o)))
            .withRequestBody(matchingJsonPath("$.lines[0].orderLineId", equalTo("L2"))));
    // 남은 0 인데 더 집으면 409
    Res over =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            Map.of(
                "executionType",
                "PICK",
                "workerId",
                "w1",
                "fromLocation",
                "A-S1-01-1-01",
                "toLocation",
                "PAK-A-01",
                "lines",
                List.of(
                    Map.of(
                        "orderLineId",
                        "L1",
                        "sku",
                        "E-00000001",
                        "lotId",
                        "LOT-A",
                        "pickedQty",
                        1))));
    assertThat(over.status()).isEqualTo(409);
    assertThat(call("GET", VIEWER, "/tasks/" + pick, null, null).body().path("state").asString())
        .isEqualTo("COMPLETED");
    assertThat(call("GET", VIEWER, "/tasks/" + pack, null, null).body().path("state").asString())
        .isEqualTo("READY");

    // 포장: w2 배정·시작, 포장 위치는 집품분 위치에서 하나로 정해진다
    assigned(pack, "w2", 1);
    assertThat(call("GET", VIEWER, "/tasks/" + pack, null, null).body().path("state").asString())
        .isEqualTo("ASSIGNED");
    assertThat(start(W2, pack, "w2", 1).status()).isEqualTo(201);
    assertThat(
            call("GET", VIEWER, "/tasks/" + pack, null, null)
                .body()
                .path("packingLocations")
                .toString())
        .contains("PAK-A-01");
    plan(o, 1, 1, 0, 0, 1, 0);
    inventoryCommits(
        "PACK", 201, Map.of("status", "APPLIED", "resultId", "CMT-P", "appliedQty", 2));
    // 집품보다 많이 담으면 409
    Res tooMuch =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "contents",
                List.of(Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 3))));
    assertThat(tooMuch.status()).isEqualTo(409);
    // 첫 포장: L1 2개(LOT 은 서버가 기한 빠른 순 — LOT-A, LOT-B)
    Res p1 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "weightG",
                600,
                "contents",
                List.of(Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 2))));
    assertThat(p1.status()).as(p1.body().toString()).isEqualTo(201);
    assertThat(p1.body().path("state").asString()).isEqualTo("SEALED");
    assertThat(p1.body().path("qrCode").asString()).startsWith("PKG:");
    assertThat(p1.body().path("contents").findValuesAsString("lotId"))
        .containsExactly("LOT-A", "LOT-B");
    assertThat(outbox("ShipmentReady", s)).isZero();
    // 두 번째 포장(냉장 L2) → PACK 완료, 출고 READY, PackingFinished·ShipmentReady
    plan(o, 1, 1, 1, 1, 1, 0);
    Res p2 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "temperatureZone",
                "CHILLED",
                "contents",
                List.of(Map.of("orderLineId", "L2", "sku", "E-00000002", "qty", 1))));
    assertThat(p2.status()).as(p2.body().toString()).isEqualTo(201);
    JsonNode shipment = call("GET", VIEWER, "/shipments/" + s, null, null).body();
    assertThat(shipment.path("state").asString()).isEqualTo("READY");
    assertThat(shipment.path("packages")).hasSize(2);
    assertThat(call("GET", VIEWER, "/tasks/" + pack, null, null).body().path("state").asString())
        .isEqualTo("COMPLETED");
    assertThat(outbox("PackingFinished", s)).isEqualTo(1);
    assertThat(outbox("ShipmentReady", s)).isEqualTo(1);
  }

  @Test
  void shortageBlocksTaskAndStopAfterPickIsPending() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);

    // 결품 보고만(집품 0) → 재고 호출 없이 BLOCKED, LineShortageReported
    Res shortage =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            Map.of(
                "executionType",
                "PICK",
                "workerId",
                "w1",
                "lines",
                List.of(
                    Map.of(
                        "orderLineId",
                        "L2",
                        "sku",
                        "E-00000002",
                        "pickedQty",
                        0,
                        "shortage",
                        Map.of("qty", 1, "reason", "DAMAGED")))));
    assertThat(shortage.status()).as(shortage.body().toString()).isEqualTo(202);
    JsonNode t = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(t.path("state").asString()).isEqualTo("BLOCKED");
    assertThat(t.path("blockReason").asString()).contains("L2").contains("DAMAGED");
    assertThat(outbox("LineShortageReported", o)).isEqualTo(1);
    inv.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions")));

    // 피킹이 시작된 주문의 중지 → PENDING(작업은 지우지 않는다, IV-08 전)
    Res stop =
        call(
            "POST",
            SVC,
            "/internal/stop-requests",
            UUID.randomUUID().toString(),
            Map.of(
                "stopRequestId",
                "STOP-" + o,
                "orderId",
                o,
                "scope",
                "FULL",
                "lines",
                List.of(
                    Map.of("orderLineId", "L1", "qty", 2), Map.of("orderLineId", "L2", "qty", 1))));
    assertThat(stop.body().path("state").asString())
        .as(stop.body().toString())
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(cancelled_qty) FROM task_line WHERE task_id = ?", Integer.class, pick))
        .isZero();
  }
}
