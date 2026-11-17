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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
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
  @LocalServerPort int port;

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

  JsonNode receipt(String approvalId) {
    return mapper
        .readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE topic = 'platform.approval-receipt.v1' AND aggregateid = ?",
                String.class,
                approvalId))
        .path("payload");
  }

  int grantApplied(String approvalId) {
    return count(
        "SELECT count(*) FROM outbox_event WHERE topic = 'platform.auth.v1' AND type = 'GrantApplied' AND aggregateid LIKE ?",
        approvalId + ":%");
  }

  JsonNode internal(String path) {
    String body =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/api/v1" + path)
            .header("X-Source-System", "hr-service")
            .retrieve()
            .body(String.class);
    return mapper.readTree(body);
  }

  /**
   * HR-2 안전망(2026-11-17): 이미 유효·예정인 같은 권한(역할 같음 + 센터 겹침 + 기간이 지금 이후로 겹침)과 겹치는 승인 행은 새 grant 를 만들지
   * 않고 회신 REJECTED(GRANT_ALREADY_ACTIVE, domainRef = 기존 grantId) — GrantApplied 없음. 다른 센터·기존 권한이 끝난
   * 뒤 시작·같은 승인 안의 앞 행과 겹침(같은 트랜잭션)·회수된 권한은 예전처럼. 내부 조회(0.11.0)는 토큰 없이 역할 목록과 끝나지 않은 grant 를 준다.
   */
  @Test
  void duplicateApprovedLineDoesNotCreateSecondGrant() throws Exception {
    String op = "OP-GA-" + UUID.randomUUID();
    store.insertAccount(op, "ACTIVE", List.of(), List.of(), Instant.now());
    LocalDate today = LocalDate.now(SEOUL);
    String receipts =
        "SELECT count(*) FROM outbox_event WHERE topic = 'platform.approval-receipt.v1' AND aggregateid = ?";

    String first = "AP-" + UUID.randomUUID();
    decided(
        first,
        "GRANT",
        "employee-auth-service",
        op,
        "APPROVED",
        List.of(line("1", "MFC_WORKER", List.of("MFC-IT-01"), today, today.plusDays(30))));
    assertThat(awaitCount(receipts, 1, first)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM employee_grant WHERE approval_id = ?", first))
        .isEqualTo(1);

    String second = "AP-" + UUID.randomUUID();
    decided(
        second,
        "GRANT",
        "employee-auth-service",
        op,
        "APPROVED",
        List.of(
            // 센터 하나가 겹치고 기간도 겹친다(더 늦게 끝남 = 연장도 막는다)
            line("1", "MFC_WORKER", List.of("MFC-IT-01", "MFC-IT-02"), today, today.plusDays(60)),
            line("2", "MFC_WORKER", List.of("MFC-IT-03"), today, null), // 다른 센터
            line("3", "MFC_WORKER", List.of("MFC-IT-01"), today.plusDays(31), today.plusDays(40)),
            line("4", "CS_AGENT", List.of(), today, null),
            line("5", "CS_AGENT", List.of(), today.plusDays(5), null))); // 같은 승인의 4행과 겹침
    assertThat(awaitCount(receipts, 1, second)).isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT line_id FROM employee_grant WHERE approval_id = ? ORDER BY line_id",
                String.class,
                second))
        .containsExactly("2", "3", "4");
    JsonNode r = receipt(second);
    assertThat(r.path("result").asString()).isEqualTo("PARTIAL");
    assertThat(r.path("lines").get(0).path("result").asString()).isEqualTo("REJECTED");
    assertThat(r.path("lines").get(0).path("reasonCode").asString())
        .isEqualTo("GRANT_ALREADY_ACTIVE");
    assertThat(r.path("lines").get(0).path("domainRef").asString()).isEqualTo(first + ":1");
    assertThat(r.path("lines").get(1).path("result").asString()).isEqualTo("APPLIED");
    assertThat(r.path("lines").get(4).path("reasonCode").asString())
        .isEqualTo("GRANT_ALREADY_ACTIVE");
    assertThat(r.path("lines").get(4).path("domainRef").asString()).isEqualTo(second + ":4");
    assertThat(grantApplied(second)).as("반영한 행만 GrantApplied").isEqualTo(3);

    // 회수된 권한은 막지 않는다(효력 끝 = 회수 시각)
    store.revokeGrant(first + ":1", Instant.now(), "OP-IT-HR", "시험 회수", "employee-auth-service");
    String third = "AP-" + UUID.randomUUID();
    decided(
        third,
        "GRANT",
        "employee-auth-service",
        op,
        "APPROVED",
        List.of(line("1", "MFC_WORKER", List.of("MFC-IT-01"), today, today.plusDays(10))));
    assertThat(awaitCount(receipts, 1, third)).isEqualTo(1);
    assertThat(receipt(third).path("result").asString()).isEqualTo("APPLIED");
    assertThat(grantApplied(third)).isEqualTo(1);

    // 내부 조회(0.11.0, 토큰 없음): 끝나지 않은 grant 만 / 전부
    JsonNode activeOrFuture = internal("/internal/employees/" + op + "/grants?activeOrFuture=true");
    List<String> ids = new java.util.ArrayList<>();
    activeOrFuture.path("items").forEach(i -> ids.add(i.path("grantId").asString()));
    assertThat(ids)
        .containsExactlyInAnyOrder(second + ":2", second + ":3", second + ":4", third + ":1");
    assertThat(activeOrFuture.path("items").get(0).has("approverId"))
        .as("승인자·사유는 싣지 않는다")
        .isFalse();
    assertThat(internal("/internal/employees/" + op + "/grants").path("items")).hasSize(5);
    assertThat(internal("/internal/employees/OP-NOBODY/grants").path("items")).isEmpty();
    JsonNode roles = internal("/internal/roles").path("items");
    Map<String, Boolean> scoped = new LinkedHashMap<>();
    roles.forEach(i -> scoped.put(i.path("code").asString(), i.path("centerScoped").asBoolean()));
    assertThat(scoped)
        .containsEntry("MFC_WORKER", true)
        .containsEntry("MFC_SUPERVISOR", true)
        .containsEntry("CS_AGENT", false);
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
