package com.pmart.employeeauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.employeeauth.application.EmployeeTokens;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

/**
 * hr-service 직원 사건(platform.employee.v1, 실제 Kafka) → 로그인 상태: 등록(계정·Keycloak 연결·근무 시작일), 정지(세션 종료 +
 * SubjectBlocked outbox), 복귀, 퇴사, 늦게 온 옛 사건 무시, 근무 시작일 전 로그인 거절. 관리 경로로 정지해도 SubjectBlocked.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.employee-auth.issuer=http://employee-auth.test",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
class EmployeeEventsIT extends PostgresKafkaContainerSupport {

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate jdbc;
  @Autowired EmployeeAuthStore store;
  @LocalServerPort int port;

  void send(String type, String op, long version, Map<String, Object> payload) {
    Map<String, Object> p = new LinkedHashMap<>(payload);
    p.put("operatorId", op);
    kafka
        .send(
            "platform.employee.v1",
            op,
            EventEnvelopeFixtures.envelope(type)
                .from("hr-service")
                .aggregate("Employee", op, version)
                .payload(p)
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

  String state(String op) {
    List<String> r =
        jdbc.queryForList(
            "SELECT state FROM employee_account WHERE operator_id = ?", String.class, op);
    return r.isEmpty() ? null : r.getFirst();
  }

  int blocks(String op) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox_event WHERE topic = 'platform.subject-block.v1' AND aggregateid = ?",
        Integer.class,
        op);
  }

  static Map<String, Object> registered(String sub, LocalDate start) {
    return Map.of(
        "employeeNo",
        "P2026-00099",
        "keycloakUserId",
        sub,
        "mailAddress",
        "x@pmart.com",
        "employmentType",
        "REGULAR",
        "affiliation",
        Map.of("type", "CENTER", "code", "MFC-IT-01"),
        "startDate",
        start.toString());
  }

  @Test
  void lifecycleFromHrEvents() throws Exception {
    String op = "OP-EV-" + UUID.randomUUID();
    String sub = UUID.randomUUID().toString();
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    send("EmployeeRegistered", op, 1, registered(sub, today));
    assertThat(await(() -> state(op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    assertThat(store.linkedOperator(sub)).contains(op);
    assertThat(store.account(op, false).orElseThrow().roles()).as("권한은 approval 뒤").isEmpty();

    // 세션 하나 만들어 두고 정지 → 세션 끝 + SubjectBlocked
    Instant now = Instant.now();
    store.insertSession(
        "ESES-" + op,
        op,
        "d1",
        "mfc",
        "it",
        now,
        now.plusSeconds(3600),
        now.plusSeconds(1800),
        null,
        null);
    send("EmployeeSuspended", op, 2, Map.of("reason", "SUSPENDED", "effectiveAt", now.toString()));
    assertThat(await(() -> state(op), "DISABLED"::equals)).isEqualTo("DISABLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM employee_session WHERE session_id = ?",
                String.class,
                "ESES-" + op))
        .isEqualTo("REVOKED");
    assertThat(blocks(op)).isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE topic = 'platform.subject-block.v1' AND aggregateid = ?",
            String.class,
            op);
    assertThat(payload).contains("\"issuer\": \"http://employee-auth.test\"").contains("OPERATOR");

    // 늦게 온 옛 사건(v1 다시)은 무시
    send("EmployeeRegistered", op, 1, registered(sub, today));
    send(
        "EmployeeReinstated",
        op,
        3,
        Map.of("effectiveAt", Instant.now().toString(), "startDate", today.toString()));
    assertThat(await(() -> state(op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    assertThat(store.account(op, false).orElseThrow().hrVersion()).isEqualTo(3L);

    send("EmployeeLeft", op, 4, Map.of("effectiveAt", Instant.now().toString()));
    assertThat(await(() -> state(op), "LEFT"::equals)).isEqualTo("LEFT");
    assertThat(blocks(op)).isEqualTo(2);
  }

  @Test
  void loginBeforeStartDateIsRefused() throws Exception {
    String op = "OP-EV-" + UUID.randomUUID();
    send(
        "EmployeeRegistered",
        op,
        1,
        registered(
            UUID.randomUUID().toString(), LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3)));
    assertThat(await(() -> state(op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    // 로그인 콜백까지 간 것처럼 티켓을 직접 만들고 교환해 본다
    String ticket = EmployeeTokens.random();
    String verifier = EmployeeTokens.random();
    store.insertTicket(
        EmployeeTokens.hash(ticket),
        op,
        "mfc",
        "d1",
        EmployeeTokens.s256(verifier),
        Instant.now(),
        Instant.now().plusSeconds(60),
        "pmart-mfc://login",
        null);
    int status =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build()
            .post()
            .uri("/employee-auth/tokens")
            .headers(
                h -> {
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "mfc-app");
                  h.set("X-Device-Id", "d1");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("ticket", ticket, "codeVerifier", verifier))
            .exchange((q, r) -> r.getStatusCode().value());
    assertThat(status).as("근무 시작일 전").isEqualTo(403);
  }

  void grant(String grantId, String op, String role, String center) {
    jdbc.update(
        "INSERT INTO employee_grant (grant_id, operator_id, role, centers, valid_from, valid_until,"
            + " approval_id, line_id, approver_id, reason, applied_at)"
            + " VALUES (?, ?, ?, ARRAY[?]::varchar[], now() - interval '1 day', NULL, ?, ?, 'OP-APPROVER', '시험', now())",
        grantId,
        op,
        role,
        center,
        grantId.split(":")[0],
        grantId.split(":")[1]);
  }

  /**
   * 소속 변경(2026-11-16 BO-01): 이전 센터가 들어 있는 grant 는 previousCenterAccessUntil 에 끝나고(회수 기록
   * SYSTEM:hr-service), 다른 센터 grant 는 그대로. 본사 부서에서 옮긴 경우(until 없음)는 grant 를 건드리지 않는다.
   */
  @Test
  void affiliationChangeEndsPreviousCenterGrants() throws Exception {
    String op = "OP-AFF-" + UUID.randomUUID();
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    send("EmployeeRegistered", op, 1, registered(UUID.randomUUID().toString(), today));
    assertThat(await(() -> state(op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    grant("AP-" + op + ":1", op, "MFC_WORKER", "MFC-IT-01");
    grant("AP-" + op + ":2", op, "MFC_WORKER", "MFC-IT-09");

    Instant until = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
    send(
        "EmployeeAffiliationChanged",
        op,
        2,
        Map.of(
            "previous", Map.of("type", "CENTER", "code", "MFC-IT-01"),
            "affiliation", Map.of("type", "CENTER", "code", "MFC-IT-02"),
            "changedAt", Instant.now().toString(),
            "previousCenterAccessUntil", until.toString()));
    String ended =
        await(
            () ->
                jdbc.queryForObject(
                    "SELECT coalesce(revoked_by, '') FROM employee_grant WHERE grant_id = ?",
                    String.class,
                    "AP-" + op + ":1"),
            v -> !v.isEmpty());
    assertThat(ended).isEqualTo("SYSTEM:hr-service");
    assertThat(
            jdbc.queryForObject(
                    "SELECT valid_until FROM employee_grant WHERE grant_id = ?",
                    java.sql.Timestamp.class,
                    "AP-" + op + ":1")
                .toInstant())
        .as("그날 안에 인계 — 다음 날 00:00 에 끝")
        .isEqualTo(until);
    assertThat(
            jdbc.queryForObject(
                "SELECT revoked_by FROM employee_grant WHERE grant_id = ?",
                String.class,
                "AP-" + op + ":2"))
        .as("다른 센터 권한은 그대로")
        .isNull();
    assertThat(store.account(op, false).orElseThrow().hrVersion()).isEqualTo(2L);
  }
}
