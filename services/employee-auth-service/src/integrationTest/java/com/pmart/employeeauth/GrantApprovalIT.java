package com.pmart.employeeauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.employeeauth.application.EmployeeAccess;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 권한 승인(ADR-0056 5-4): approval-service ApprovalDecided(type GRANT, 실제 Kafka) → 승인 행이 grant 로 저장되고,
 * 토큰 역할·센터 = 계정 값 ∪ 지금 유효한 grant(시작 전은 빠짐). 모르는 역할 행은 거절 → 회신 PARTIAL. 행마다
 * GrantApplied(platform.auth.v1), 다른 승인 종류·반려는 반영하지 않음.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.employee-auth.issuer=http://employee-auth.test",
      "pmart.messaging.outbox.delete-after-insert=false",
      // EmployeeEventsIT 와 같은 문맥을 재사용하지 않게(그 시험의 컨테이너는 클래스가 끝나면 멈춘다)
      "pmart.test.context=grant-approval"
    })
class GrantApprovalIT extends PostgresKafkaContainerSupport {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate jdbc;
  @Autowired EmployeeAuthStore store;
  @Autowired EmployeeAccess access;
  @Autowired ObjectMapper mapper;

  void decided(
      String approvalId,
      String type,
      String targetService,
      String op,
      String decision,
      List<Map<String, Object>> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("approvalId", approvalId);
    p.put("changeRequestId", "CR-" + UUID.randomUUID());
    p.put("type", type);
    p.put("decision", decision);
    p.put(
        "targetRef",
        Map.of("targetType", "EmployeeGrant", "targetId", op, "targetService", targetService));
    p.put("lines", lines);
    p.put("conditions", List.of());
    p.put("approverId", "RULE:GRANT-MFC-WORKER-OWN-CENTER@v1");
    p.put("dossierVersion", 2);
    p.put("decidedAt", Instant.now().toString());
    kafka
        .send(
            "platform.approval.v1",
            approvalId,
            EventEnvelopeFixtures.envelope("ApprovalDecided")
                .from("approval-service")
                .aggregate("Approval", approvalId, 2)
                .payload(p)
                .buildJson())
        .join();
  }

  static Map<String, Object> line(
      String id, String role, List<String> centers, LocalDate from, LocalDate to) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("role", role);
    attrs.put("centers", centers);
    attrs.put("validFrom", from.toString());
    if (to != null) {
      attrs.put("validTo", to.toString());
    }
    return Map.of("lineId", id, "decision", "APPROVED", "targetRef", "x", "attributes", attrs);
  }

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  int awaitCount(String sql, int n, Object... args) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    int c = count(sql, args);
    while (c < n && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      c = count(sql, args);
    }
    return c;
  }

  @Test
  void approvedLinesBecomeGrantsAndTokenAccess() throws Exception {
    String op = "OP-GA-" + UUID.randomUUID();
    Instant now = Instant.now();
    store.insertAccount(op, "ACTIVE", List.of("CS_AGENT"), List.of(), now);
    LocalDate today = LocalDate.now(SEOUL);
    String ap = "AP-" + UUID.randomUUID();
    decided(
        ap,
        "GRANT",
        "employee-auth-service",
        op,
        "APPROVED",
        List.of(
            line("1", "MFC_WORKER", List.of("MFC-IT-01"), today, today.plusDays(30)),
            line("2", "PRICE_APPROVER", List.of(), today.plusDays(2), null), // 아직 시작 전
            line("3", "NO_SUCH_ROLE", List.of("MFC-IT-01"), today, null)));

    String receipts =
        "SELECT count(*) FROM outbox_event WHERE topic = 'platform.approval-receipt.v1' AND aggregateid = ?";
    assertThat(awaitCount(receipts, 1, ap)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM employee_grant WHERE approval_id = ?", ap)).isEqualTo(2);

    JsonNode receipt =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE topic = 'platform.approval-receipt.v1' AND aggregateid = ?",
                String.class,
                ap));
    assertThat(receipt.path("payload").path("result").asString()).isEqualTo("PARTIAL");
    assertThat(receipt.path("payload").path("receiverService").asString())
        .isEqualTo("employee-auth-service");
    assertThat(receipt.path("payload").path("lines").get(2).path("reasonCode").asString())
        .isEqualTo("UNKNOWN_ROLE");
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE topic = 'platform.auth.v1' AND type = 'GrantApplied' AND aggregateid LIKE ?",
                ap + ":%"))
        .isEqualTo(2);

    // 토큰 = 계정(CS_AGENT) ∪ 지금 유효한 grant(MFC_WORKER@MFC-IT-01). PRICE_APPROVER 는 시작 전
    EmployeeAccess.Access a = access.of(store.account(op, false).orElseThrow(), Instant.now());
    assertThat(a.roles()).containsExactly("CS_AGENT", "MFC_WORKER");
    assertThat(a.centers()).containsExactly("MFC-IT-01");
    EmployeeAccess.Access later =
        access.of(
            store.account(op, false).orElseThrow(),
            today.plusDays(3).atStartOfDay(SEOUL).toInstant());
    assertThat(later.roles()).containsExactly("CS_AGENT", "MFC_WORKER", "PRICE_APPROVER");
    EmployeeAccess.Access ended =
        access.of(
            store.account(op, false).orElseThrow(),
            today.plusDays(31).atStartOfDay(SEOUL).toInstant());
    assertThat(ended.roles()).as("validTo 다음 날부터 무효").containsExactly("CS_AGENT", "PRICE_APPROVER");
  }

  @Test
  void otherTypesAndRejectionsAreNotApplied() throws Exception {
    String op = "OP-GA-" + UUID.randomUUID();
    LocalDate today = LocalDate.now(SEOUL);
    String price = "AP-" + UUID.randomUUID();
    String rejected = "AP-" + UUID.randomUUID();
    String marker = "AP-" + UUID.randomUUID();
    decided(
        price,
        "PRICE",
        "pricing-service",
        op,
        "APPROVED",
        List.of(line("1", "MFC_WORKER", List.of(), today, null)));
    decided(
        rejected,
        "GRANT",
        "employee-auth-service",
        op,
        "REJECTED",
        List.of(line("1", "MFC_WORKER", List.of(), today, null)));
    // 같은 파티션 순서가 아니므로 표시용 승인 하나를 보내 처리 끝을 기다린다
    decided(
        marker,
        "GRANT",
        "employee-auth-service",
        op,
        "APPROVED",
        List.of(line("1", "MD", List.of(), today, null)));
    assertThat(awaitCount("SELECT count(*) FROM employee_grant WHERE approval_id = ?", 1, marker))
        .isEqualTo(1);
    Thread.sleep(1000);
    assertThat(
            count(
                "SELECT count(*) FROM employee_grant WHERE approval_id IN (?, ?)", price, rejected))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE topic = 'platform.approval-receipt.v1' AND aggregateid IN (?, ?)",
                price,
                rejected))
        .isZero();
  }
}
