package com.pmart.approval;

import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 직원 권한 요청(ADR-0056 5-4): hr-service GrantRequested(실제 Kafka) → 자동 승인 규칙에 맞는 행(소속 센터의 MFC_WORKER)은
 * 바로 승인(승인자 = 규칙) + ApprovalDecided, 나머지는 심사 중 승인 → 승인자(OPERATOR_ADMIN, 요청자와 다름)가 결정.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class GrantRequestIT extends PostgresKafkaContainerSupport {

  static final String RULE = "RULE:GRANT-MFC-WORKER-OWN-CENTER@v1";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  void send(
      String op, String hrStaff, Map<String, Object> affiliation, List<Map<String, Object>> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("requestId", "GR-" + UUID.randomUUID());
    p.put("operatorId", op);
    p.put("requestedBy", hrStaff);
    p.put("affiliation", affiliation);
    p.put("lines", lines);
    p.put("requestedAt", Instant.now().toString());
    kafka
        .send(
            "platform.employee.v1",
            op,
            EventEnvelopeFixtures.envelope("GrantRequested")
                .from("hr-service")
                .aggregate("Employee", op, 1)
                .payload(p)
                .buildJson())
        .join();
  }

  static Map<String, Object> line(String key, String role, List<String> centers) {
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("lineKey", key);
    l.put("role", role);
    l.put("centers", centers);
    l.put("validFrom", "2026-11-13");
    l.put("validTo", "2027-02-28");
    l.put("reason", "신규 입사");
    return l;
  }

  List<Map<String, Object>> approvals(String op) {
    return jdbc.queryForList(
        "SELECT approval_id, review_state, execution_state, requester_id, unit::text AS unit"
            + " FROM approval WHERE type = 'GRANT' AND target_ref = ? ORDER BY submitted_at, approval_id",
        op);
  }

  List<Map<String, Object>> awaitApprovals(String op, int n) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    List<Map<String, Object>> r = approvals(op);
    while (r.size() < n && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      r = approvals(op);
    }
    return r;
  }

  JsonNode envelope(String approvalId) {
    return mapper.readTree(
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'ApprovalDecided'",
            String.class,
            approvalId));
  }

  JsonNode decided(String approvalId) {
    return envelope(approvalId).path("payload");
  }

  @Test
  void ownCenterWorkerIsAutoApprovedOthersWaitForApprover() throws Exception {
    String op = "OP-GR-" + UUID.randomUUID();
    String hr = "OP-HR-" + UUID.randomUUID();
    send(
        op,
        hr,
        Map.of("type", "CENTER", "code", "MFC-IT-01"),
        List.of(
            line("1", "MFC_WORKER", List.of("MFC-IT-01")),
            line("2", "MFC_WORKER", List.of("MFC-IT-02")), // 다른 센터 → 심사
            line("3", "MFC_SUPERVISOR", List.of("MFC-IT-01")))); // 다른 역할 → 심사

    List<Map<String, Object>> rows = awaitApprovals(op, 2);
    assertThat(rows).hasSize(2);
    Map<String, Object> auto =
        rows.stream()
            .filter(r -> "APPROVED".equals(r.get("review_state")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> manual =
        rows.stream()
            .filter(r -> "REVIEW_PENDING".equals(r.get("review_state")))
            .findFirst()
            .orElseThrow();
    assertThat(auto.get("requester_id")).isEqualTo(hr);

    // 자동 승인: 승인자 = 규칙, 행은 1번만, ApprovalDecided 행 속성(역할·센터·기간) 그대로
    String autoId = (String) auto.get("approval_id");
    assertThat(
            jdbc.queryForObject(
                "SELECT approver_id FROM approval_decision WHERE approval_id = ?",
                String.class,
                autoId))
        .isEqualTo(RULE);
    JsonNode ev = decided(autoId);
    assertThat(ev.path("type").asString()).isEqualTo("GRANT");
    assertThat(ev.path("decision").asString()).isEqualTo("APPROVED");
    assertThat(ev.path("approverId").asString()).isEqualTo(RULE);
    assertThat(ev.path("targetRef").path("targetService").asString())
        .isEqualTo("employee-auth-service");
    assertThat(ev.path("targetRef").path("targetId").asString()).isEqualTo(op);
    assertThat(ev.path("effectiveFrom").asString()).isEqualTo("2026-11-12T15:00:00Z");
    assertThat(ev.path("lines")).hasSize(1);
    JsonNode l1 = ev.path("lines").get(0);
    assertThat(l1.path("lineId").asString()).isEqualTo("1");
    assertThat(l1.path("attributes").path("role").asString()).isEqualTo("MFC_WORKER");
    assertThat(l1.path("attributes").path("validTo").asString()).isEqualTo("2027-02-28");
    assertThat(envelope(autoId).path("actor").path("type").asString()).isEqualTo("SYSTEM");

    // 심사: 2·3번 행, 아직 결정 사건 없음
    String manualId = (String) manual.get("approval_id");
    assertThat(mapper.readTree((String) manual.get("unit")).path("lines")).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'ApprovalDecided'",
                Integer.class,
                manualId))
        .isZero();

    RestClient http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    // 요청자(인사팀 직원)는 OPERATOR_ADMIN 이어도 자기 요청을 승인할 수 없다
    assertThat(decide(http, manualId, TestJwt.operator(hr, List.of("OPERATOR_ADMIN"), List.of())))
        .isEqualTo(403);
    // 승인 역할 없는 사람
    assertThat(decide(http, manualId, TestJwt.operator("OP-X", List.of("HR_ADMIN"), List.of())))
        .isEqualTo(403);
    assertThat(
            decide(
                http, manualId, TestJwt.operator("OP-ADMIN", List.of("OPERATOR_ADMIN"), List.of())))
        .isEqualTo(202);
    JsonNode ev2 = decided(manualId);
    assertThat(ev2.path("approverId").asString()).isEqualTo("OP-ADMIN");
    assertThat(ev2.path("lines")).hasSize(2);
  }

  @Test
  void hqAffiliationIsNeverAutoApproved() throws Exception {
    String op = "OP-GR-" + UUID.randomUUID();
    send(
        op,
        "OP-HR-1",
        Map.of("type", "HQ_DEPARTMENT", "code", "MFC-IT-01"),
        List.of(line("1", "MFC_WORKER", List.of("MFC-IT-01"))));
    List<Map<String, Object>> rows = awaitApprovals(op, 1);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("review_state")).isEqualTo("REVIEW_PENDING");
  }

  int decide(RestClient http, String approvalId, String token) {
    return http.post()
        .uri("/approvals/{id}/decisions", approvalId)
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "backoffice-web");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("decision", "APPROVE"))
        .exchange((q, r) -> r.getStatusCode().value());
  }
}
