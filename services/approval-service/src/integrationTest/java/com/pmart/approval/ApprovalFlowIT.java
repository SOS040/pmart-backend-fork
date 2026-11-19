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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 승인 최소 흐름(ADR-0014): 변경 요청 생성 → 제출 → 결정 → ApprovalDecided 가 Outbox 에 스키마 검증을 통과해 기록된다. 실제
 * PostgreSQL·Kafka, 시험 토큰.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.approval.approver-roles.PRICE=PRICE_APPROVER",
      // 검색 동의어(ADR-0073 단계 B) — 설정 저장소에 넣기 전이라 여기서
      "pmart.approval.approver-roles.SEARCH_SYNONYM=OPERATOR_ADMIN",
      "pmart.approval.requester-roles.SEARCH_SYNONYM=MD,OPERATOR_ADMIN"
    })
@Import(TestJwtConfiguration.class)
class ApprovalFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());
  static final String LEAD = TestJwt.operator("lead-1", List.of("PRICE_APPROVER"), List.of());
  static final String ADMIN = TestJwt.operator("admin-1", List.of("OPERATOR_ADMIN"), List.of());

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
            .build();
  }

  private Map<String, Object> priceChange(String sku) {
    return Map.of(
        "type", "PRICE",
        "targetService", "pricing-service",
        "targetRef", sku,
        "allowedActions", List.of("APPLY_PRICE_VERSION"),
        "summary", "기본가 변경",
        "requestedEffectiveFrom", "2026-11-01T00:00:00+09:00",
        "lines",
            List.of(
                Map.of(
                    "lineKey",
                    sku,
                    "requestedAmount",
                    3500,
                    "attributes",
                    Map.of("basePrice", 3500))));
  }

  private record Res(int status, JsonNode body) {}

  private Res post(String path, String token, String key, Object body) {
    var spec =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      spec = spec.body(body);
    }
    var entity = spec.retrieve().toEntity(String.class);
    return new Res(entity.getStatusCode().value(), mapper.readTree(entity.getBody()));
  }

  private Res get(String path, String token) {
    var entity =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(entity.getStatusCode().value(), mapper.readTree(entity.getBody()));
  }

  @Test
  void createSubmitApprovePublishesApprovalDecided() {
    String sku = "E-" + UUID.randomUUID().toString().substring(0, 8);
    String key = UUID.randomUUID().toString();

    Res created = post("/change-requests", MD, key, priceChange(sku));
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.body().get("requestState").asString()).isEqualTo("DRAFT");
    String crId = created.body().get("changeRequestId").asString();

    // 같은 키·같은 본문 → 같은 결과(새로 만들지 않음)
    Res replay = post("/change-requests", MD, key, priceChange(sku));
    assertThat(replay.body().get("changeRequestId").asString()).isEqualTo(crId);
    // 같은 키·다른 본문 → 409
    assertThat(post("/change-requests", MD, key, priceChange(sku + "X")).status()).isEqualTo(409);

    Res submitted =
        post("/change-requests/" + crId + "/submissions", MD, UUID.randomUUID().toString(), null);
    assertThat(submitted.status()).isEqualTo(202);
    String approvalId = submitted.body().get("resultId").asString();

    // BR-41: 대상 참조로 그 승인을 찾는다(정본 화면이 배치의 승인 ID 를 되찾을 때)
    Res byTarget = get("/approvals?targetRef=" + sku, LEAD);
    assertThat(byTarget.status()).isEqualTo(200);
    assertThat(byTarget.body().get("items")).hasSize(1);
    assertThat(byTarget.body().get("items").get(0).get("approvalId").asString())
        .isEqualTo(approvalId);
    assertThat(get("/approvals?targetRef=" + sku + "-none", LEAD).body().get("items")).isEmpty();

    // 같은 대상 두 번째 제출 → 이중 발급 금지
    String cr2 =
        post("/change-requests", MD, UUID.randomUUID().toString(), priceChange(sku))
            .body()
            .get("changeRequestId")
            .asString();
    Res dup =
        post("/change-requests/" + cr2 + "/submissions", MD, UUID.randomUUID().toString(), null);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().get("code").asString()).isEqualTo("DUPLICATE_APPROVAL");

    // 자기승인 금지, 권한 없는 운영자 금지
    Map<String, Object> approve = Map.of("decision", "APPROVE", "comment", "승인");
    assertThat(
            post(
                    "/approvals/" + approvalId + "/decisions",
                    MD,
                    UUID.randomUUID().toString(),
                    approve)
                .status())
        .isEqualTo(403);

    Res decided =
        post(
            "/approvals/" + approvalId + "/decisions", LEAD, UUID.randomUUID().toString(), approve);
    assertThat(decided.status()).isEqualTo(202);
    assertThat(decided.body().get("status").asString()).isEqualTo("APPLIED");

    Res approval = get("/approvals/" + approvalId, LEAD);
    assertThat(approval.body().get("reviewState").asString()).isEqualTo("APPROVED");
    assertThat(approval.body().get("executionState").asString()).isEqualTo("VALIDATING");
    assertThat(approval.body().get("decisions")).hasSize(1);
    assertThat(approval.body().get("effectiveFrom").asString()).isEqualTo("2026-10-31T15:00:00Z");
    // BR-45: 값 없는 수량·금액은 null 이 아니라 생략(계약 Money·integer 는 nullable 아님)
    JsonNode line = approval.body().get("lines").get(0);
    assertThat(line.get("requestedAmount").asInt()).isEqualTo(3500);
    assertThat(line.has("requestedQty")).isFalse();
    assertThat(line.has("approvedAmount")).isFalse();

    // 결정된 승인은 다시 결정할 수 없다
    assertThat(
            post(
                    "/approvals/" + approvalId + "/decisions",
                    LEAD,
                    UUID.randomUUID().toString(),
                    approve)
                .status())
        .isEqualTo(409);

    // Outbox: ChangeRequestSubmitted·ApprovalDecided (스키마 ENFORCE 통과해야 기록됨)
    List<String> types =
        jdbc.queryForList(
            "SELECT type FROM outbox_event WHERE aggregateid IN (?, ?) ORDER BY created_at",
            String.class,
            crId,
            approvalId);
    assertThat(types).containsExactly("ChangeRequestSubmitted", "ApprovalDecided");
    JsonNode event =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'ApprovalDecided'",
                String.class,
                approvalId));
    JsonNode p = event.get("payload");
    assertThat(p.get("decision").asString()).isEqualTo("APPROVED");
    assertThat(p.get("targetRef").get("targetType").asString()).isEqualTo("Price");
    assertThat(p.get("targetRef").get("targetId").asString()).isEqualTo(sku);
    assertThat(p.get("lines").get(0).get("attributes").get("basePrice").asInt()).isEqualTo(3500);
    assertThat(p.get("approverId").asString()).isEqualTo("lead-1");
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE aggregateid = ? AND type = 'ApprovalDecided'",
                String.class,
                approvalId))
        .isEqualTo("platform.approval.v1");
  }

  @Test
  void unconfiguredApprovalTypeCannotBeDecided() {
    Map<String, Object> body =
        Map.of(
            "type", "POLICY", // 승인자 역할을 설정하지 않은 종류(CENTER_MASTER 는 2026-10-31 설정됨)
            "targetService", "center-master-service",
            "targetRef", "MFC-" + UUID.randomUUID(),
            "allowedActions", List.of("APPLY_CENTER_CHANGE"),
            "summary", "센터 기준정보 변경");
    String crId =
        post("/change-requests", MD, UUID.randomUUID().toString(), body)
            .body()
            .get("changeRequestId")
            .asString();
    String approvalId =
        post("/change-requests/" + crId + "/submissions", MD, UUID.randomUUID().toString(), null)
            .body()
            .get("resultId")
            .asString();
    Res res =
        post(
            "/approvals/" + approvalId + "/decisions",
            LEAD,
            UUID.randomUUID().toString(),
            Map.of("decision", "APPROVE"));
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.body().get("code").asString()).isEqualTo("CONFIG_REQUIRED");
  }

  @Test
  void nonOperatorTokensAreForbidden() {
    Res res =
        post(
            "/change-requests",
            TestJwt.customer("c-1"),
            UUID.randomUUID().toString(),
            priceChange("E-00000001"));
    assertThat(res.status()).isEqualTo(403);
    // 변경 요청 조회도 운영자만(권한 빈틈 정리 2026-11-17)
    assertThat(get("/change-requests/CR-none", TestJwt.customer("c-1")).status()).isEqualTo(403);
  }

  /** 제출 → 승인까지(가격). approvalId. */
  private String approvedPrice(String sku) {
    String crId =
        post("/change-requests", MD, UUID.randomUUID().toString(), priceChange(sku))
            .body()
            .get("changeRequestId")
            .asString();
    String approvalId =
        post("/change-requests/" + crId + "/submissions", MD, UUID.randomUUID().toString(), null)
            .body()
            .get("resultId")
            .asString();
    Res decided =
        post(
            "/approvals/" + approvalId + "/decisions",
            LEAD,
            UUID.randomUUID().toString(),
            Map.of("decision", "APPROVE"));
    assertThat(decided.status()).isEqualTo(202);
    return approvalId;
  }

  /** pricing-service 가 내는 반영 회신을 흉내 낸다(ADR-0039). */
  private void receipt(
      String approvalId, String receiver, String result, List<Map<String, Object>> lines) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("receiverService", receiver);
    payload.put("receiptSeq", 1);
    payload.put("result", result);
    payload.put("lines", lines);
    String json =
        EventEnvelopeFixtures.envelope("ApplicationReceiptRecorded")
            .from(receiver)
            .aggregate("ApplicationReceipt", approvalId, 1)
            .payload(payload)
            .buildJson();
    kafka.send("platform.approval-receipt.v1", approvalId, json).join();
  }

  private int receiptCount(String approvalId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM application_receipt WHERE approval_id = ?",
            Integer.class,
            approvalId);
    return n == null ? 0 : n;
  }

  private void awaitReceipts(String approvalId, int count) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (receiptCount(approvalId) < count && Instant.now().isBefore(until)) {
      Thread.sleep(200);
    }
    assertThat(receiptCount(approvalId)).isEqualTo(count);
  }

  @Test
  void rejectedReceiptFromTargetServiceShowsOnTheApproval() throws Exception {
    String sku = "E-" + UUID.randomUUID().toString().substring(0, 8);
    String approvalId = approvedPrice(sku);
    assertThat(get("/approvals/" + approvalId, LEAD).body().get("receipts")).isEmpty();

    // 정본(pricing-service)이 기간 겹침으로 거절했다고 회신 → 승인은 APPROVED 그대로, 실행 축 REJECTED
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("lineKey", sku);
    line.put("result", "REJECTED");
    line.put("reasonCode", "PRICE_SCHEDULE_CONFLICT");
    receipt(approvalId, "pricing-service", "REJECTED", List.of(line));
    awaitReceipts(approvalId, 1);

    Res approval = get("/approvals/" + approvalId, LEAD);
    assertThat(approval.body().get("reviewState").asString()).isEqualTo("APPROVED");
    assertThat(approval.body().get("executionState").asString()).isEqualTo("REJECTED");
    assertThat(approval.body().get("deliveryState").asString()).isEqualTo("ACKNOWLEDGED");
    JsonNode r = approval.body().get("receipts").get(0);
    assertThat(r.get("receiverService").asString()).isEqualTo("pricing-service");
    assertThat(r.get("result").asString()).isEqualTo("REJECTED");
    assertThat(r.get("lines").get(0).get("lineKey").asString()).isEqualTo(sku);
    assertThat(r.get("lines").get(0).get("reasonCode").asString())
        .isEqualTo("PRICE_SCHEDULE_CONFLICT");

    // 목록 API 와 실행 축 필터
    Res list = get("/approvals/" + approvalId + "/receipts", LEAD);
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().get("items")).hasSize(1);
    assertThat(get("/approvals?executionState=REJECTED&targetRef=" + sku, LEAD).body().get("items"))
        .hasSize(1);
    assertThat(get("/approvals/AP-none/receipts", LEAD).status()).isEqualTo(404);

    // 같은 수신자·순번 재전송은 한 번만
    receipt(approvalId, "pricing-service", "REJECTED", List.of(line));
    // 다른 수신자의 회신은 기록만 하고 실행 축은 그대로
    receipt(approvalId, "search-service", "APPLIED", List.of());
    awaitReceipts(approvalId, 2);
    Thread.sleep(1000);
    assertThat(receiptCount(approvalId)).isEqualTo(2);
    assertThat(get("/approvals/" + approvalId, LEAD).body().get("executionState").asString())
        .isEqualTo("REJECTED");
  }

  @Test
  void appliedReceiptAndUnknownApproval() throws Exception {
    String sku = "E-" + UUID.randomUUID().toString().substring(0, 8);
    String approvalId = approvedPrice(sku);
    // 모르는 승인의 회신은 버린다(소비가 막히지 않아야 뒤 회신이 반영된다)
    receipt("AP-" + UUID.randomUUID(), "pricing-service", "APPLIED", List.of());
    receipt(
        approvalId,
        "pricing-service",
        "APPLIED",
        List.of(Map.of("lineKey", sku, "result", "APPLIED", "domainVersion", 2)));
    awaitReceipts(approvalId, 1);
    Res approval = get("/approvals/" + approvalId, LEAD);
    assertThat(approval.body().get("executionState").asString()).isEqualTo("APPLIED");
    assertThat(
            approval.body().get("receipts").get(0).get("lines").get(0).get("domainVersion").asInt())
        .isEqualTo(2);
  }

  /** 검색 동의어(ADR-0073 단계 B): MD 요청 → 운영 관리자 승인 → ApprovalDecided 에 기준 버전·규칙 행이 실린다. */
  @Test
  void searchSynonymRequestedByMdApprovedByAdmin() {
    Map<String, Object> change =
        new LinkedHashMap<>(
            Map.of(
                "type", "SEARCH_SYNONYM",
                "targetService", "search-service",
                "targetRef", "pmart-product-synonyms",
                "targetExpectedVersion", 0,
                "allowedActions", List.of("APPLY_SYNONYM_RULES"),
                "summary", "계란·달걀 동의어",
                "lines",
                    List.of(
                        Map.of(
                            "lineKey",
                            "egg",
                            "attributes",
                            Map.of("op", "UPSERT", "terms", List.of("계란", "달걀"))))));
    // 요청 역할(MD·OPERATOR_ADMIN)이 아니면 403
    assertThat(post("/change-requests", LEAD, UUID.randomUUID().toString(), change).status())
        .isEqualTo(403);
    // 기준 버전이 없으면 400
    Map<String, Object> noVersion = new LinkedHashMap<>(change);
    noVersion.remove("targetExpectedVersion");
    Res bad = post("/change-requests", MD, UUID.randomUUID().toString(), noVersion);
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body().toString()).contains("targetExpectedVersion");

    Res created = post("/change-requests", MD, UUID.randomUUID().toString(), change);
    assertThat(created.status()).isEqualTo(201);
    String crId = created.body().get("changeRequestId").asString();
    Res submitted =
        post("/change-requests/" + crId + "/submissions", MD, UUID.randomUUID().toString(), null);
    assertThat(submitted.status()).isEqualTo(202);
    String approvalId = submitted.body().get("resultId").asString();

    Map<String, Object> approve = Map.of("decision", "APPROVE");
    // 승인자 역할 OPERATOR_ADMIN 만(가격 승인자는 403)
    assertThat(
            post(
                    "/approvals/" + approvalId + "/decisions",
                    LEAD,
                    UUID.randomUUID().toString(),
                    approve)
                .status())
        .isEqualTo(403);
    assertThat(
            post(
                    "/approvals/" + approvalId + "/decisions",
                    ADMIN,
                    UUID.randomUUID().toString(),
                    approve)
                .status())
        .isEqualTo(202);

    JsonNode p =
        mapper
            .readTree(
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'ApprovalDecided'",
                    String.class,
                    approvalId))
            .get("payload");
    assertThat(p.get("type").asString()).isEqualTo("SEARCH_SYNONYM");
    assertThat(p.get("decision").asString()).isEqualTo("APPROVED");
    assertThat(p.at("/targetRef/targetType").asString()).isEqualTo("SynonymSet");
    assertThat(p.at("/targetRef/targetService").asString()).isEqualTo("search-service");
    assertThat(p.at("/targetRef/expectedVersion").asInt()).isEqualTo(0);
    assertThat(p.at("/lines/0/lineId").asString()).isEqualTo("egg");
    assertThat(p.at("/lines/0/attributes/terms").toString()).isEqualTo("[\"계란\",\"달걀\"]");
  }
}
