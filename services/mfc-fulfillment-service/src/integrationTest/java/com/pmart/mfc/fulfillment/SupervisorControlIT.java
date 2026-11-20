package com.pmart.mfc.fulfillment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
 * 센터 관리자 작업 조작(MFC 시뮬레이터 R-29 2단계, ADR-0077): 작업 중지(BLOCKED, 담당·재고 그대로) · 해제 · 부분 종료. 재고·배정 정본은
 * WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.fulfillment.mfc-inventory-base-url=${inv.url}",
      "pmart.fulfillment.mfc-capacity-base-url=${cap.url}",
      "pmart.fulfillment.ready-sweep-interval=PT1H",
      "pmart.fulfillment.handover-sweep-interval=PT1H",
      "pmart.fulfillment.blocked-check-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "inv", baseUrlProperties = "inv.url"),
  @ConfigureWireMock(name = "cap", baseUrlProperties = "cap.url")
})
class SupervisorControlIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-T9";
  static final String SVC = TestJwt.service("order-orchestration-service");
  static final String W1 = TestJwt.operator("w1", List.of("MFC_WORKER"), List.of(MFC));
  static final String SUP = TestJwt.operator("sup1", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String OTHER_SUP =
      TestJwt.operator("sup9", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));
  static final String ADMIN = TestJwt.operator("OP-admin", List.of("OPERATOR_ADMIN"), List.of());

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
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .atPriority(10)
            .willReturn(json(200, Map.of("items", List.of()))));
    inv.stubFor(
        WireMock.post(urlPathMatching("/api/v1/internal/reservations/.*/location-allocations"))
            .willReturn(json(200, Map.of("parts", List.of()))));
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
    return call(method, token, path, key, body, null);
  }

  Res call(
      String method,
      String token,
      String path,
      @Nullable String key,
      @Nullable Object body,
      @Nullable Integer expectedVersion) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  if (key != null) {
                    h.set("Idempotency-Key", key);
                    h.set("X-Source-System", "mfc-backoffice");
                  }
                  if (expectedVersion != null) {
                    h.set("X-Expected-Version", String.valueOf(expectedVersion));
                  }
                });
    var e =
        (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 접수된 작업 지시: 줄 L1 2개, L2 1개(PICK READY·PACK WAITING). */
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
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/pick-plan"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId", "RSV-" + o, "state", "CONFIRMED", "parts", List.of()))));
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

  /** 배정 투영 + capacity 활성 배정 조회 스텁. eligibility = 배정 작업자가 지금 맡을 수 없는 이유(null 이면 적격). */
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
    activeAssignment(taskId, worker, version, null);
    await(
        "SELECT COUNT(*) FROM task WHERE task_id = '"
            + taskId
            + "' AND assignment_version = "
            + version);
  }

  void activeAssignment(
      String taskId, @Nullable String worker, int version, @Nullable String eligibility) {
    List<Object> items = new java.util.ArrayList<>();
    if (worker != null) {
      Map<String, Object> a = new LinkedHashMap<>();
      a.put("taskId", taskId);
      a.put("workerId", worker);
      a.put("assignmentVersion", version);
      a.put("state", "ACTIVE");
      if (eligibility != null) {
        a.put("workerEligibility", eligibility);
      }
      items.add(a);
    }
    cap.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/assignments"))
            .withQueryParam("taskId", equalTo(taskId))
            .willReturn(json(200, Map.of("items", items))));
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

  Res start(String token, String taskId, String worker, int version) {
    return call(
        "POST",
        token,
        "/tasks/" + taskId + "/starts",
        UUID.randomUUID().toString(),
        Map.of("workerId", worker, "assignmentVersion", version));
  }

  /** L1 LOT-A 1개 집품(재고 PICK 반영). */
  Res pickOne(String taskId) {
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("PICK")))
            .willReturn(
                json(201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1))));
    return call(
        "POST",
        W1,
        "/tasks/" + taskId + "/executions",
        UUID.randomUUID().toString(),
        Map.of(
            "executionType",
            "PICK",
            "workerId",
            "w1",
            "fromLocation",
            "A-S1-01-1-01",
            "toLocation",
            "CRT-001",
            "lines",
            List.of(
                Map.of(
                    "orderLineId", "L1", "sku", "E-00000001", "lotId", "LOT-A", "pickedQty", 1))));
  }

  JsonNode task(String taskId) {
    return call("GET", SUP, "/tasks/" + taskId, null, null).body();
  }

  Res block(String token, String taskId, String key, Object body) {
    return call("POST", token, "/tasks/" + taskId + "/blocks", key, body);
  }

  String lastEvent(String type, String aggregateId) {
    return jdbc.queryForObject(
        "SELECT payload::text FROM outbox_event WHERE type = ? AND aggregateid = ?"
            + " ORDER BY created_at DESC, (payload->>'aggregateVersion')::int DESC LIMIT 1",
        String.class,
        type,
        aggregateId);
  }

  int outbox(String type, String aggregateId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
        Integer.class,
        type,
        aggregateId);
  }

  // ---------- 1. 작업 중지 ----------

  @Test
  void supervisorBlocksTaskKeepingAssigneeAndWorkerIsRejected() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    Res picked = pickOne(pick);
    assertThat(picked.status()).as(picked.body().toString()).isEqualTo(202);
    int before = task(pick).path("version").asInt();

    // 권한: 작업자·다른 센터 관리자 403, 사유 없음 400
    assertThat(block(W1, pick, UUID.randomUUID().toString(), Map.of("reason", "x")).status())
        .isEqualTo(403);
    assertThat(block(OTHER_SUP, pick, UUID.randomUUID().toString(), Map.of("reason", "x")).status())
        .isEqualTo(403);
    assertThat(block(SUP, pick, UUID.randomUUID().toString(), Map.of("reason", " ")).status())
        .isEqualTo(400);

    // 결과를 기다리는 실적이 있으면 409 EXECUTION_IN_FLIGHT(결과 불명 먼저)
    jdbc.update(
        "INSERT INTO task_execution (execution_id, task_id, request_id, worker_id, execution_type,"
            + " lines, state, recorded_at, updated_at) VALUES (?, ?, ?, 'w1', 'PICK', '[]'::jsonb,"
            + " 'UNKNOWN_RESULT', now(), now())",
        "EXE-" + o,
        pick,
        "rq-" + o);
    Res inFlight = block(SUP, pick, UUID.randomUUID().toString(), Map.of("reason", "설비 고장"));
    assertThat(inFlight.status()).isEqualTo(409);
    assertThat(inFlight.body().path("code").asString()).isEqualTo("EXECUTION_IN_FLIGHT");
    jdbc.update("UPDATE task_execution SET state = 'REJECTED' WHERE execution_id = ?", "EXE-" + o);

    // 옛 버전 409, 맞는 버전이면 BLOCKED — 담당·시작·완료량 그대로
    Res stale =
        call(
            "POST",
            SUP,
            "/tasks/" + pick + "/blocks",
            UUID.randomUUID().toString(),
            Map.of("reason", "설비 고장"),
            before - 1);
    assertThat(stale.body().path("code").asString()).isEqualTo("VERSION_CONFLICT");
    String key = UUID.randomUUID().toString();
    Res blocked =
        call("POST", SUP, "/tasks/" + pick + "/blocks", key, Map.of("reason", "컨베이어 고장"), before);
    assertThat(blocked.status()).as(blocked.body().toString()).isEqualTo(200);
    assertThat(blocked.body().path("action").asString()).isEqualTo("BLOCK");
    assertThat(blocked.body().path("fromState").asString()).isEqualTo("IN_PROGRESS");
    assertThat(blocked.body().path("toState").asString()).isEqualTo("BLOCKED");
    assertThat(blocked.body().path("actorId").asString()).isEqualTo("sup1");
    assertThat(blocked.body().path("workerId").asString()).isEqualTo("w1");
    assertThat(blocked.body().path("taskVersion").asInt()).isEqualTo(before + 1);
    // 같은 키 다시 = 같은 결과
    Res again =
        call("POST", SUP, "/tasks/" + pick + "/blocks", key, Map.of("reason", "컨베이어 고장"), before);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("controlId").asString())
        .isEqualTo(blocked.body().path("controlId").asString());
    JsonNode t = task(pick);
    assertThat(t.path("state").asString()).isEqualTo("BLOCKED");
    assertThat(t.path("blockKind").asString()).isEqualTo("SUPERVISOR");
    assertThat(t.path("blockReason").asString()).isEqualTo("관리자 작업 중지: 컨베이어 고장");
    assertThat(t.path("assignment").path("workerId").asString()).isEqualTo("w1");
    assertThat(t.path("completedQty").asInt()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT started_by FROM task WHERE task_id = ?", String.class, pick))
        .isEqualTo("w1");
    // 사건: TaskProgressed BLOCKED + 관리자·사유
    assertThat(lastEvent("TaskProgressed", pick))
        .contains("\"controlledBy\": \"sup1\"")
        .contains("\"blockKind\": \"SUPERVISOR\"")
        .contains("\"state\": \"BLOCKED\"");

    // 막힌 동안 작업자 실적·시작 409 TASK_BLOCKED, 다시 멈추기 409 INVALID_STATE
    Res exec = pickOne(pick);
    assertThat(exec.status()).isEqualTo(409);
    assertThat(exec.body().path("code").asString()).isEqualTo("TASK_BLOCKED");
    Res st = start(W1, pick, "w1", 1);
    assertThat(st.status()).isEqualTo(409);
    assertThat(st.body().path("code").asString()).isEqualTo("TASK_BLOCKED");
    assertThat(
            block(SUP, pick, UUID.randomUUID().toString(), Map.of("reason", "또"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("INVALID_STATE");
    // 재고·배정 호출 없음(예약·담당 그대로)
    inv.verify(
        0,
        WireMock.postRequestedFor(
            urlPathMatching(
                "/api/v1/internal/reservations/.*/(releases|shortfall-releases|cancel-returns)")));
    cap.verify(0, WireMock.postRequestedFor(urlPathMatching("/api/v1/internal/assignments/.*")));

    // 조작 기록
    JsonNode list = call("GET", SUP, "/tasks/" + pick + "/controls", null, null).body();
    assertThat(list.path("items")).hasSize(1);
    assertThat(list.path("items").get(0).path("reason").asString()).isEqualTo("컨베이어 고장");

    // 본사 운영 관리자는 어느 센터든(READY 작업)
    String o2 = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String pick2 = accept(o2) + "-PICK";
    Res byAdmin = block(ADMIN, pick2, UUID.randomUUID().toString(), Map.of("reason", "오주문 확인"));
    assertThat(byAdmin.status()).as(byAdmin.body().toString()).isEqualTo(200);
    assertThat(byAdmin.body().path("fromState").asString()).isEqualTo("READY");
    assertThat(outbox("TaskProgressed", pick2)).isEqualTo(1);
  }

  Res unblock(String token, String taskId, String key, Map<String, Object> body) {
    return call("POST", token, "/tasks/" + taskId + "/unblocks", key, body);
  }

  // ---------- 2. 막힘 해제 ----------

  @Test
  void unblockGoesBackToLiveAssigneeOtherwiseReadyAndReleased() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String pick = accept(o) + "-PICK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    assertThat(pickOne(pick).status()).isEqualTo(202);
    assertThat(block(SUP, pick, UUID.randomUUID().toString(), Map.of("reason", "고장")).status())
        .isEqualTo(200);

    // 실물 확인 표시 필수 — 빠지면 400(계약 필수 칸), false 면 422 PHYSICAL_CHECK_REQUIRED, 작업자 403
    assertThat(unblock(SUP, pick, UUID.randomUUID().toString(), Map.of("reason", "수리 끝")).status())
        .isEqualTo(400);
    Res noCheck =
        unblock(
            SUP,
            pick,
            UUID.randomUUID().toString(),
            Map.of("reason", "수리 끝", "cartChecked", false));
    assertThat(noCheck.status()).isEqualTo(422);
    assertThat(noCheck.body().path("code").asString()).isEqualTo("PHYSICAL_CHECK_REQUIRED");
    assertThat(
            unblock(
                    W1,
                    pick,
                    UUID.randomUUID().toString(),
                    Map.of("reason", "수리 끝", "cartChecked", true))
                .status())
        .isEqualTo(403);

    // 담당 배정이 살아 있고 맡을 수 있음 → ASSIGNED(그 사람, 시작은 다시), 완료량 그대로
    Res back =
        unblock(
            SUP, pick, UUID.randomUUID().toString(), Map.of("reason", "수리 끝", "cartChecked", true));
    assertThat(back.status()).as(back.body().toString()).isEqualTo(200);
    assertThat(back.body().path("toState").asString()).isEqualTo("ASSIGNED");
    assertThat(back.body().path("workerId").asString()).isEqualTo("w1");
    JsonNode t = task(pick);
    assertThat(t.path("state").asString()).isEqualTo("ASSIGNED");
    assertThat(t.path("blockKind").isNull()).isTrue();
    assertThat(t.path("completedQty").asInt()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT started_by FROM task WHERE task_id = ?", String.class, pick))
        .isNull();
    cap.verify(0, WireMock.postRequestedFor(urlPathMatching("/api/v1/internal/assignments/.*")));
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201); // 이어서 — 남은 양만

    // 다시 멈춤 → 담당이 정지됨(workerEligibility) → capacity 배정 해제 → READY(누구나)
    assertThat(block(SUP, pick, UUID.randomUUID().toString(), Map.of("reason", "교대")).status())
        .isEqualTo(200);
    activeAssignment(pick, "w1", 1, "WORKER_NOT_ACTIVE");
    cap.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/assignments/" + pick + "/releases"))
            .willReturn(json(200, Map.of("taskId", pick, "released", true))));
    String key = UUID.randomUUID().toString();
    Map<String, Object> body = Map.of("reason", "담당 퇴근", "cartChecked", true);
    Res ready = unblock(ADMIN, pick, key, body);
    assertThat(ready.status()).as(ready.body().toString()).isEqualTo(200);
    assertThat(ready.body().path("toState").asString()).isEqualTo("READY");
    assertThat(ready.body().path("workerId").isNull()).isTrue();
    assertThat(unblock(ADMIN, pick, key, body).body().path("controlId").asString())
        .isEqualTo(ready.body().path("controlId").asString());
    cap.verify(
        1,
        WireMock.postRequestedFor(
                urlPathEqualTo("/api/v1/internal/assignments/" + pick + "/releases"))
            .withRequestBody(matchingJsonPath("$.workerId", equalTo("w1"))));
    t = task(pick);
    assertThat(t.path("state").asString()).isEqualTo("READY");
    assertThat(t.path("assignment").path("workerId").isNull()).isTrue();
    assertThat(t.path("completedQty").asInt()).isEqualTo(1);
    assertThat(lastEvent("TaskProgressed", pick))
        .contains("\"state\": \"READY\"")
        .contains("\"controlledBy\": \"OP-admin\"");
    assertThat(call("GET", SUP, "/tasks/" + pick + "/controls", null, null).body().path("items"))
        .hasSize(4);
    // 이미 풀림 → 409 INVALID_STATE
    assertThat(
            unblock(SUP, pick, UUID.randomUUID().toString(), body).body().path("code").asString())
        .isEqualTo("INVALID_STATE");
  }

  @Test
  void unblockRefusesShortageBlockAndKeepsBlockWhenCapacityIsDown() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String pick = accept(o) + "-PICK";
    // 결품으로 막힌 작업(block_kind 없음)은 관리자가 풀지 않는다
    jdbc.update(
        "UPDATE task SET state = 'BLOCKED', block_reason = '결품 보고: L1 1개 SHORT(재확보 못 함)'"
            + " WHERE task_id = ?",
        pick);
    Map<String, Object> body = Map.of("reason", "확인", "cartChecked", true);
    Res shortage = unblock(SUP, pick, UUID.randomUUID().toString(), body);
    assertThat(shortage.status()).isEqualTo(409);
    assertThat(shortage.body().path("code").asString()).isEqualTo("BLOCKED_BY_SHORTAGE");

    // 관리자 중지 + capacity 조회 실패 → 503, 그대로 BLOCKED
    jdbc.update("UPDATE task SET state = 'READY', block_reason = NULL WHERE task_id = ?", pick);
    assertThat(block(SUP, pick, UUID.randomUUID().toString(), Map.of("reason", "점검")).status())
        .isEqualTo(200);
    cap.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/assignments"))
            .willReturn(aResponse().withStatus(503)));
    Res down = unblock(SUP, pick, UUID.randomUUID().toString(), body);
    assertThat(down.status()).isEqualTo(503);
    assertThat(task(pick).path("state").asString()).isEqualTo("BLOCKED");
    // 담당이 없던 작업 → READY
    activeAssignment(pick, null, 0, null);
    Res ok = unblock(SUP, pick, UUID.randomUUID().toString(), body);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("toState").asString()).isEqualTo("READY");
  }
}
