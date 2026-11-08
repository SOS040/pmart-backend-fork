package com.pmart.mfc.capacity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
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
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 배정 추천(WK-09, 실제 CP-SAT)·확정(WK-10 재검증·STALE). 작업 정본(fulfillment)은 WireMock. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.capacity.plan.mfc-fulfillment-base-url=${ful.url}",
      "pmart.capacity.plan.task-minutes.PICK=7",
      "pmart.capacity.plan.task-minutes.PACK=4",
      "pmart.capacity.plan.time-limit=PT5S",
      "pmart.capacity.plan.packing-stations=4"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "ful", baseUrlProperties = "ful.url")})
class AssignmentPlanIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-PLAN-1";
  static final String SUP = TestJwt.operator("sup-p", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String WORKER = TestJwt.operator("pk1", List.of("MFC_WORKER"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("ful")
  WireMockServer ful;

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

  Res send(String method, String token, String path, @Nullable Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                });
    var e =
        (body == null
                ? spec
                : spec.contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body)))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void register(String w, List<String> types) {
    send(
        "POST",
        SUP,
        "/mfcs/" + MFC + "/workers",
        Map.of(
            "workerId",
            w,
            "displayName",
            w,
            "duty",
            Map.of("taskTypes", types, "temperatureZones", List.of("AMBIENT"))));
  }

  static Map<String, Object> task(
      String id, String type, String state, int version, @Nullable String worker, int dueIn) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("taskId", id);
    m.put("taskType", type);
    m.put("state", state);
    m.put("version", version);
    m.put("mfcId", MFC);
    if (worker != null) {
      m.put("assignment", Map.of("workerId", worker));
    }
    if (dueIn > 0) {
      m.put("promisedReadyAt", Instant.now().plusSeconds(dueIn * 60L).toString());
    }
    return m;
  }

  void tasks(List<Map<String, Object>> items, String... states) {
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/tasks"))
            .withQueryParam("mfcId", WireMock.equalTo(MFC))
            .withQueryParam("state", havingExactly(states))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of("items", items, "page", Map.of("size", items.size()))))));
  }

  /**
   * 다음 작업 받기(0.6.0): 업무 안의 담당 없는 READY 작업 중 약속 이른 PICK → (그다음) 복귀 순, 한 번에 하나(다시 부르면 같은 배정), 본인만, 줄
   * 작업 없음 409, 등록 근무 밖 409.
   */
  @Test
  void nextTaskIsAssignedOneAtATimeBySystem() {
    String s = UUID.randomUUID().toString().substring(0, 6);
    String a = "nx1-" + s;
    String b = "nx2-" + s;
    String c = "nx3-" + s;
    register(a, List.of("PICK", "RETURN"));
    register(b, List.of("PICK"));
    register(c, List.of("RETURN"));
    String early = "T-NE-" + s;
    String late = "T-NL-" + s;
    String ret = "T-NR-" + s;
    tasks(
        List.of(
            task(late, "PICK", "READY", 1, null, 90),
            task(ret, "RETURN", "READY", 1, null, 0),
            task(early, "PICK", "READY", 1, null, 30),
            task("T-NO-" + s, "PACK", "READY", 1, null, 10)),
        "READY",
        "ASSIGNED",
        "IN_PROGRESS");
    String ta = TestJwt.operator(a, List.of("MFC_WORKER"), List.of(MFC));
    String tb = TestJwt.operator(b, List.of("MFC_WORKER"), List.of(MFC));
    String tc = TestJwt.operator(c, List.of("MFC_WORKER"), List.of(MFC));
    String path = "/mfcs/" + MFC + "/workers/";
    // 본인만
    assertThat(send("POST", ta, path + b + "/next-tasks", null).status()).isEqualTo(403);
    Res first = send("POST", ta, path + a + "/next-tasks", null);
    assertThat(first.status()).as(first.body().toString()).isEqualTo(201);
    assertThat(first.body().path("taskId").asString()).isEqualTo(early);
    assertThat(first.body().path("source").asString()).isEqualTo("SYSTEM");
    // 한 번에 하나 — 다시 부르면 같은 배정
    Res again = send("POST", ta, path + a + "/next-tasks", null);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("taskId").asString()).isEqualTo(early);
    // 다른 작업자는 남은 것 중 가장 급한 PICK, 복귀만 하는 작업자는 복귀
    assertThat(send("POST", tb, path + b + "/next-tasks", null).body().path("taskId").asString())
        .isEqualTo(late);
    assertThat(send("POST", tc, path + c + "/next-tasks", null).body().path("taskId").asString())
        .isEqualTo(ret);
    // 더 받을 작업 없음
    String d = "nx4-" + s;
    register(d, List.of("PICK"));
    String td = TestJwt.operator(d, List.of("MFC_WORKER"), List.of(MFC));
    Res none = send("POST", td, path + d + "/next-tasks", null);
    assertThat(none.status()).isEqualTo(409);
    assertThat(none.body().path("code").asString()).isEqualTo("NO_TASK_AVAILABLE");
    // 등록 근무 밖이면 시스템이 작업을 주지 않음
    Instant later = Instant.now().plusSeconds(3 * 3600);
    send(
        "POST",
        SUP,
        path + d + "/shifts",
        Map.of("startAt", later.toString(), "endAt", later.plusSeconds(4 * 3600).toString()));
    Res off = send("POST", td, path + d + "/next-tasks", null);
    assertThat(off.status()).isEqualTo(409);
    assertThat(off.body().path("code").asString()).isEqualTo("OFF_SHIFT");
  }

  @Test
  void proposesByDutyAndBusyTimeThenCommitsAfterRecheck() {
    register("pk1", List.of("PICK"));
    register("pk2", List.of("PICK", "PACK"));
    String s = UUID.randomUUID().toString().substring(0, 6);
    String p1 = "T-P1-" + s;
    String p2 = "T-P2-" + s;
    String k1 = "T-K1-" + s;
    String rx = "T-RX-" + s;
    List<Map<String, Object>> all =
        List.of(
            task(p1, "PICK", "READY", 1, null, 20),
            task(p2, "PICK", "READY", 1, null, 0),
            task(k1, "PACK", "READY", 1, null, 0),
            task(rx, "RECEIVE", "READY", 1, null, 0),
            task("T-BUSY-" + s, "PICK", "IN_PROGRESS", 3, "pk1", 0));
    tasks(all, "READY", "ASSIGNED", "IN_PROGRESS");

    assertThat(
            send("POST", WORKER, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 60))
                .status())
        .isEqualTo(403);
    Res created =
        send("POST", SUP, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 60));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    JsonNode plan = created.body();
    assertThat(plan.path("state").asString()).isEqualTo("PROPOSED");
    assertThat(plan.path("solverStatus").asString()).isIn("OPTIMAL", "FEASIBLE");
    assertThat(plan.path("items")).hasSize(3);
    assertThat(plan.path("unassigned")).hasSize(1);
    // 쓴 설정(임시 구현값)·근무 미등록 작업자·한계가 결과에 남는다
    assertThat(plan.path("settings").path("taskMinutes").path("PICK").asInt()).isEqualTo(7);
    assertThat(plan.path("settings").path("status").asString())
        .isEqualTo("TEMPORARY_IMPLEMENTATION_VALUES");
    assertThat(plan.path("availabilityUnverifiedWorkers").toString()).contains("pk1", "pk2");
    assertThat(plan.path("limitations").toString()).contains("IN_PROGRESS_FULL_DURATION");
    assertThat(plan.path("unassigned").path(0).path("taskId").asString()).isEqualTo(rx);
    assertThat(plan.path("unassigned").path(0).path("reason").asString())
        .isEqualTo("CONFIG_REQUIRED");
    Instant start = Instant.parse(plan.path("horizonStart").asString());
    for (JsonNode i : plan.path("items")) {
      if (i.path("taskId").asString().equals(k1)) {
        assertThat(i.path("workerId").asString()).isEqualTo("pk2"); // PACK 업무는 pk2 만
      }
      if (i.path("workerId").asString().equals("pk1")) {
        // pk1 은 진행 중 PICK(7분) 뒤에만
        assertThat(Instant.parse(i.path("startAt").asString()))
            .isAfterOrEqualTo(start.plusSeconds(7 * 60));
      }
    }
    // 추천만으로는 배정이 생기지 않는다
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_assignment WHERE task_id IN (?,?,?)",
                Integer.class,
                p1,
                p2,
                k1))
        .isZero();

    // 확정: 작업이 그대로(READY·같은 version) → 3건 배정 + 사건(planId)
    tasks(all.subList(0, 4), "READY");
    String planId = plan.path("planId").asString();
    Res committed = send("POST", SUP, "/assignment-plans/" + planId + "/commits", null);
    assertThat(committed.status()).as(committed.body().toString()).isEqualTo(200);
    assertThat(committed.body().path("state").asString()).isEqualTo("COMMITTED");
    assertThat(committed.body().path("assignments")).hasSize(3);
    assertThat(committed.body().path("assignments").path(0).path("planId").asString())
        .isEqualTo(planId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'AssignmentPublished'"
                    + " AND payload::text LIKE ?",
                Integer.class,
                "%" + planId + "%"))
        .isEqualTo(3);
    // 다시 확정 → 지금 상태
    assertThat(
            send("POST", SUP, "/assignment-plans/" + planId + "/commits", null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("COMMITTED");
  }

  @Test
  void commitIsRejectedAsStaleWhenATaskChangedAfterPlanning() {
    register("pk3", List.of("PICK"));
    String q = "T-Q1-" + UUID.randomUUID().toString().substring(0, 6);
    tasks(List.of(task(q, "PICK", "READY", 1, null, 0)), "READY", "ASSIGNED", "IN_PROGRESS");
    JsonNode plan =
        send("POST", SUP, "/assignment-plans", Map.of("mfcId", MFC, "horizonMinutes", 30)).body();
    assertThat(plan.path("state").asString()).isEqualTo("PROPOSED");
    String planId = plan.path("planId").asString();

    // 계산 뒤 작업 version 이 바뀜 → 아무것도 배정 안 하고 STALE
    tasks(List.of(task(q, "PICK", "READY", 2, null, 0)), "READY");
    Res stale = send("POST", SUP, "/assignment-plans/" + planId + "/commits", null);
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.body().path("code").asString()).isEqualTo("PLAN_STALE");
    JsonNode after = send("GET", SUP, "/assignment-plans/" + planId, null).body();
    assertThat(after.path("state").asString()).isEqualTo("STALE");
    assertThat(after.path("staleReasons").path(0).asString()).startsWith("TASK_CHANGED:");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_assignment WHERE task_id = ?", Integer.class, q))
        .isZero();
  }
}
