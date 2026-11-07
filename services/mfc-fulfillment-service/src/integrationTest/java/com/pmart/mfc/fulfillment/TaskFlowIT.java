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
 * 뒤 중지(작업 종료·취소 복귀·RETURN 작업, IV-08). 재고·배정 정본은 WireMock.
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
  @Autowired com.pmart.mfc.fulfillment.application.WorkStopService stops;

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
    b.put("outOfStockPreference", "CANCEL_OOS_ONLY");
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

  static Map<String, Object> shortageBody(String line, String sku, String lot, String reason) {
    String shelf = "L2".equals(line) ? "C-S1-01-1-01" : "A-S1-01-1-01";
    return Map.of(
        "executionType",
        "PICK",
        "workerId",
        "w1",
        "fromLocation",
        shelf,
        "lines",
        List.of(
            Map.of(
                "orderLineId",
                line,
                "sku",
                sku,
                "lotId",
                lot,
                "pickedQty",
                0,
                "shortage",
                Map.of("qty", 1, "reason", reason))));
  }

  @Test
  void shortageBlocksTaskAndResolutionResumesWork() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);

    // 결품 보고는 집품과 섞지 않는다(400), 위치·LOT 필수
    assertThat(
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
                                1,
                                "shortage",
                                Map.of("qty", 1, "reason", "SHORT")))))
                .status())
        .isEqualTo(400);

    // 1) L1 결품 → 재고가 같은 SKU 적격 LOT 으로 재확보(blockedQty 0) → 작업 계속(IN_PROGRESS), 결품 사건 없음
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/shortage-reports"))
            .withRequestBody(matchingJsonPath("$.orderLineId", equalTo("L1")))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "reportId",
                        "x",
                        "commitId",
                        "CMT-S1",
                        "resecuredQty",
                        1,
                        "blockedQty",
                        0))));
    Res resecured =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            shortageBody("L1", "E-00000001", "LOT-A", "SHORT"));
    assertThat(resecured.status()).as(resecured.body().toString()).isEqualTo(202);
    assertThat(resecured.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(call("GET", VIEWER, "/tasks/" + pick, null, null).body().path("state").asString())
        .isEqualTo("IN_PROGRESS");
    assertThat(outbox("LineShortageReported", o)).isZero();

    // 2) L2 파손 → 재확보 못 함(blockedQty 1) → BLOCKED, LineShortageReported(shortQty 1)
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/shortage-reports"))
            .withRequestBody(matchingJsonPath("$.orderLineId", equalTo("L2")))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "reportId",
                        "y",
                        "commitId",
                        "CMT-S2",
                        "resecuredQty",
                        0,
                        "blockedQty",
                        1))));
    Res shortage =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            shortageBody("L2", "E-00000002", "LOT-C", "DAMAGED"));
    assertThat(shortage.status()).as(shortage.body().toString()).isEqualTo(202);
    JsonNode t = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(t.path("state").asString()).isEqualTo("BLOCKED");
    assertThat(t.path("blockReason").asString()).contains("L2").contains("DAMAGED");
    assertThat(outbox("LineShortageReported", o)).isEqualTo(1);
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/shortage-reports"))
            .withRequestBody(matchingJsonPath("$.reservationId", equalTo("RSV-" + o)))
            .withRequestBody(matchingJsonPath("$.locationCode", equalTo("C-S1-01-1-01")))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("DAMAGED"))));
    inv.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions")));

    // 결품 사건에 고객 사전 선택·예약이 실린다(결품 사가 ADR-0045)
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'LineShortageReported'"
                    + " AND aggregateid = ?",
                String.class,
                o))
        .contains("\"outOfStockPreference\": \"CANCEL_OOS_ONLY\"")
        .contains("RSV-" + o);

    // 품절 상품만 취소 → 작업에서 그 수량을 빼고 BLOCKED 를 풀어 진행(IN_PROGRESS)
    String fid =
        call("GET", SVC, "/internal/work-orders?orderId=" + o, null, null)
            .body()
            .path("fulfillmentId")
            .asString();
    String rkey = UUID.randomUUID().toString();
    Map<String, Object> res = Map.of("orderLineId", "L2", "qty", 1, "resolution", "CANCEL_ITEM");
    Res resolved =
        call("POST", SVC, "/internal/work-orders/" + fid + "/shortage-resolutions", rkey, res);
    assertThat(resolved.status()).as(resolved.body().toString()).isEqualTo(200);
    assertThat(
            call("POST", SVC, "/internal/work-orders/" + fid + "/shortage-resolutions", rkey, res)
                .status())
        .isEqualTo(200);
    JsonNode after = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(after.path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(after.path("blockReason").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT cancelled_qty FROM task_line WHERE task_id = ? AND order_line_id = 'L2'",
                Integer.class,
                pick))
        .isEqualTo(1);
    // 남은 양보다 많이 → 409
    assertThat(
            call(
                    "POST",
                    SVC,
                    "/internal/work-orders/" + fid + "/shortage-resolutions",
                    UUID.randomUUID().toString(),
                    res)
                .status())
        .isEqualTo(409);
  }

  /** 피킹 뒤 전체 중지(IV-08, ADR-0046): 작업 종료·출고 취소 → 재고 취소 복귀 → RESOLVED → RETURN 작업으로 선반 복귀. */
  @Test
  void stopAfterPickClosesWorkAndReturnsPickedToShelf() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    String ret = s + "-RETURN";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));
    Res picked =
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
    assertThat(picked.body().path("status").asString()).isEqualTo("APPLIED");

    // 재고 결과 모름(500) → PENDING(작업은 이미 종료, 복귀 작업은 대기)
    String cancelPath = "/api/v1/internal/reservations/RSV-" + o + "/cancel-returns";
    inv.stubFor(WireMock.post(urlPathEqualTo(cancelPath)).willReturn(json(500, Map.of())));
    String key = UUID.randomUUID().toString();
    Map<String, Object> stopBody =
        Map.of(
            "stopRequestId",
            "STOP-" + o,
            "orderId",
            o,
            "scope",
            "FULL",
            "lines",
            List.of(Map.of("orderLineId", "L1", "qty", 2), Map.of("orderLineId", "L2", "qty", 1)));
    Res pending = call("POST", SVC, "/internal/stop-requests", key, stopBody);
    assertThat(pending.status()).as(pending.body().toString()).isEqualTo(200);
    assertThat(pending.body().path("state").asString()).isEqualTo("PENDING");
    assertThat(call("GET", VIEWER, "/tasks/" + pick, null, null).body().path("state").asString())
        .isEqualTo("CLOSED_PARTIAL");
    assertThat(
            call("GET", VIEWER, "/tasks/" + s + "-PACK", null, null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM shipment WHERE shipment_id = ?", String.class, s))
        .isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE task_id = ?", String.class, ret))
        .isEqualTo("WAITING");
    assertThat(outbox("WorkStopResult", o)).isZero();
    // 중지 뒤 집품 실적은 받지 않는다(작업 종료)
    assertThat(
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
                        "A-S1-02-1-01",
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
                                "LOT-B",
                                "pickedQty",
                                1))))
                .status())
        .isEqualTo(409);

    // 재고 반영 → 같은 키로 다시 → RESOLVED, 포장 전 단위 판정(집지 않은 양·집은 양) STOPPED, RETURN READY
    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(
                json(200, Map.of("status", "APPLIED", "resultId", "RSV-" + o, "appliedQty", 3))));
    Res done = call("POST", SVC, "/internal/stop-requests", key, stopBody);
    assertThat(done.body().path("state").asString())
        .as(done.body().toString())
        .isEqualTo("RESOLVED");
    JsonNode pkg = done.body().path("packages").path(0);
    assertThat(pkg.path("verdict").asString()).isEqualTo("STOPPED");
    assertThat(pkg.path("shipmentId").asString()).isEqualTo(s);
    assertThat(pkg.path("stoppedLines").toString())
        .contains("NOT_PICKED")
        .contains("\"workStateAtStop\":\"PICKED\"");
    assertThat(done.body().path("notStartedLines")).isEmpty();
    inv.verify(
        postRequestedFor(urlPathEqualTo(cancelPath))
            .withHeader("Idempotency-Key", equalTo("STOP-" + o))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("CANCEL_AFTER_PICK")))
            .withRequestBody(matchingJsonPath("$.returns[0].locationCode", equalTo("PAK-A-01")))
            .withRequestBody(matchingJsonPath("$.returns[0].workState", equalTo("PICKED")))
            .withRequestBody(matchingJsonPath("$.returns[0].lotId", equalTo("LOT-A"))));
    assertThat(outbox("WorkStopResult", o)).isEqualTo(1);
    assertThat(
            call("GET", SVC, "/internal/stop-requests/STOP-" + o, null, null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("RESOLVED");

    // 복귀 작업: 배정 → 시작 → 지시(포장 위치·LOT) → 남은 양 초과 409 → 선반으로 옮김(재고 MOVE) → COMPLETED
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE task_id = ?", String.class, ret))
        .isEqualTo("READY");
    assigned(ret, "w2", 1);
    assertThat(start(W2, ret, "w2", 1).status()).isEqualTo(201);
    JsonNode rd = call("GET", VIEWER, "/tasks/" + ret, null, null).body();
    assertThat(rd.path("taskType").asString()).isEqualTo("RETURN");
    JsonNode rp = rd.path("lines").path(0).path("picks").path(0);
    assertThat(rp.path("lotId").asString()).isEqualTo("LOT-A");
    assertThat(rp.path("remainingQty").asInt()).isEqualTo(1);
    assertThat(rp.path("locations").path(0).path("locationCode").asString()).isEqualTo("PAK-A-01");
    // PICK 실적은 RETURN 작업에 못 넣는다
    assertThat(
            call(
                    "POST",
                    W2,
                    "/tasks/" + ret + "/executions",
                    UUID.randomUUID().toString(),
                    returnBody("PICK", 1))
                .status())
        .isEqualTo(422);
    assertThat(
            call(
                    "POST",
                    W2,
                    "/tasks/" + ret + "/executions",
                    UUID.randomUUID().toString(),
                    returnBody("RETURN", 2))
                .body()
                .path("code")
                .asString())
        .isEqualTo("QTY_EXCEEDS_REMAINING");
    inventoryCommits(
        "MOVE", 201, Map.of("status", "APPLIED", "resultId", "CMT-M1", "appliedQty", 1));
    Res moved =
        call(
            "POST",
            W2,
            "/tasks/" + ret + "/executions",
            UUID.randomUUID().toString(),
            returnBody("RETURN", 1));
    assertThat(moved.status()).as(moved.body().toString()).isEqualTo(202);
    assertThat(moved.body().path("status").asString()).isEqualTo("APPLIED");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("MOVE")))
            .withRequestBody(matchingJsonPath("$.fromLocation", equalTo("PAK-A-01")))
            .withRequestBody(matchingJsonPath("$.toLocation", equalTo("A-S1-01-1-01")))
            .withRequestBody(matchingJsonPath("$.lines[0].lotId", equalTo("LOT-A"))));
    assertThat(call("GET", VIEWER, "/tasks/" + ret, null, null).body().path("state").asString())
        .isEqualTo("COMPLETED");
  }

  /** 재고가 취소 복귀를 거절 → PENDING + lastError, 자동 재시도(sweep)는 다시 보내지 않음 → 운영자 재시도로 RESOLVED. */
  @Test
  void rejectedCancelReturnStopsUntilOperatorRetries() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));
    assertThat(
            call(
                    "POST",
                    W1,
                    "/tasks/" + pick + "/executions",
                    UUID.randomUUID().toString(),
                    returnLikePick())
                .body()
                .path("status")
                .asString())
        .isEqualTo("APPLIED");

    String cancelPath = "/api/v1/internal/reservations/RSV-" + o + "/cancel-returns";
    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(json(409, Map.of("code", "RETURN_MISMATCH", "detail", "취소 복귀 수량이 다릅니다"))));
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
    assertThat(stop.body().path("state").asString()).isEqualTo("PENDING");
    assertThat(stop.body().path("lastError").asString()).startsWith("RETURN_MISMATCH");
    inv.verify(1, postRequestedFor(urlPathEqualTo(cancelPath)));

    // 자동 재시도는 실패로 멈춘 건을 다시 보내지 않는다
    stops.sweepPending();
    stops.sweepPending();
    inv.verify(1, postRequestedFor(urlPathEqualTo(cancelPath)));

    // 원인을 고친 뒤 운영자 재시도 → RESOLVED, 사유 지워짐, 같은 키로 재고에 다시
    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(
                json(200, Map.of("status", "APPLIED", "resultId", "RSV-" + o, "appliedQty", 3))));
    Res retried =
        call(
            "POST",
            SVC,
            "/internal/stop-requests/STOP-" + o + "/retries",
            UUID.randomUUID().toString(),
            null);
    assertThat(retried.status()).as(retried.body().toString()).isEqualTo(200);
    assertThat(retried.body().path("state").asString()).isEqualTo("RESOLVED");
    assertThat(retried.body().path("lastError").isNull()).isTrue();
    inv.verify(
        2,
        postRequestedFor(urlPathEqualTo(cancelPath))
            .withHeader("Idempotency-Key", equalTo("STOP-" + o)));
    assertThat(outbox("WorkStopResult", o)).isEqualTo(1);
    // 끝난 뒤 다시 불러도 그대로
    assertThat(
            call(
                    "POST",
                    SVC,
                    "/internal/stop-requests/STOP-" + o + "/retries",
                    UUID.randomUUID().toString(),
                    null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("RESOLVED");
    assertThat(call("POST", SVC, "/internal/stop-requests/STOP-NONE/retries", "k", null).status())
        .isEqualTo(404);
  }

  static Map<String, Object> returnLikePick() {
    return Map.of(
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
            Map.of("orderLineId", "L1", "sku", "E-00000001", "lotId", "LOT-A", "pickedQty", 1)));
  }

  static Map<String, Object> returnBody(String type, int qty) {
    return Map.of(
        "executionType",
        type,
        "workerId",
        "w2",
        "fromLocation",
        "PAK-A-01",
        "toLocation",
        "A-S1-01-1-01",
        "lines",
        List.of(
            Map.of("orderLineId", "L1", "sku", "E-00000001", "lotId", "LOT-A", "pickedQty", qty)));
  }
}
