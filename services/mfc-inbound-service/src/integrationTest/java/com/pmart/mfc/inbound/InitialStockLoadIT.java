package com.pmart.mfc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 초기 보유 반입(D2): INITIAL_STOCK 승인(ApprovalDecided, Kafka) → 센터별 반입 기록 → mfc-inventory
 * ConfirmReceipt(서비스 토큰, receiptId 멱등 키) → APPLIED. inventory 일시 장애 재시도, inventory 거절, 승인 내용 오류, 대상
 * 아닌 승인 무시. mfc-inventory·auth 는 WireMock, PostgreSQL·Kafka 는 실제.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${pmart.inbound.mfc-inventory-base-url}/api/v1/internal/service-tokens",
      "pmart.inbound.post-interval=PT1S"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class InitialStockLoadIT extends PostgresKafkaContainerSupport {

  static final String TOPIC = "platform.approval.v1";
  static final String RECEIPTS = "/api/v1/internal/receipts";
  static final String SERVICE_TOKEN = TestJwt.service("mfc-inbound-service");
  static final String OPERATOR =
      TestJwt.operator("ops-1", List.of("INVENTORY_APPROVER"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("inventory")
  WireMockServer inventory;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    inventory.resetAll();
    inventory.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "access_token",
                        SERVICE_TOKEN,
                        "token_type",
                        "Bearer",
                        "expires_in",
                        300))));
  }

  ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  void inventoryAccepts(String mfcId, String commitId) {
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(mfcId)))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "status",
                        "APPLIED",
                        "requestId",
                        "r",
                        "resultId",
                        commitId,
                        "appliedQty",
                        1))));
  }

  record Res(int status, JsonNode body) {}

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** ApprovalDecided 를 토픽에 흘린다. 같은 봉투 JSON 을 돌려준다(재전달 시험용). */
  String approve(String approvalId, String type, String decision, List<Map<String, Object>> attrs) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("type", type);
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType",
            "InitialStockLoad",
            "targetId",
            "INIT-1",
            "targetService",
            "mfc-inbound-service"));
    payload.put(
        "lines",
        attrs.stream()
            .map(
                a -> {
                  Map<String, Object> l = new LinkedHashMap<>();
                  l.put("lineId", a.get("sku"));
                  l.put("decision", "APPROVED");
                  l.put("attributes", a);
                  return l;
                })
            .toList());
    payload.put("approverId", "inv-approver-1");
    payload.put("dossierVersion", 1);
    String json =
        EventEnvelopeFixtures.envelope("ApprovalDecided")
            .from("approval-service")
            .aggregate("Approval", approvalId, 2)
            .payload(payload)
            .buildJson();
    kafka.send(TOPIC, approvalId, json).join();
    return json;
  }

  static Map<String, Object> line(String mfcId, String sku, Object qty) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("mfcId", mfcId);
    m.put("sku", sku);
    m.put("qty", qty);
    return m;
  }

  JsonNode awaitLoads(String approvalId, Predicate<JsonNode> done) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(60));
    JsonNode items =
        get(OPERATOR, "/initial-stock-loads?approvalId=" + approvalId).body().path("items");
    while (!done.test(items) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      items = get(OPERATOR, "/initial-stock-loads?approvalId=" + approvalId).body().path("items");
    }
    return items;
  }

  static boolean allIn(JsonNode items, int n, String state) {
    if (items.size() != n) {
      return false;
    }
    for (JsonNode i : items) {
      if (!state.equals(i.path("state").asString())) {
        return false;
      }
    }
    return true;
  }

  static String mfc() {
    return "MFC-IT-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void approvedInitialStockIsPostedToInventoryPerCenter() throws Exception {
    String m1 = mfc();
    String m2 = mfc();
    inventoryAccepts(m1, "CMT-1");
    inventoryAccepts(m2, "CMT-2");
    String approvalId = "AP-" + UUID.randomUUID();
    String event =
        approve(
            approvalId,
            "INITIAL_STOCK",
            "APPROVED",
            List.of(
                line(m1, "E-00000001", 10), line(m1, "B-00000002", 3), line(m2, "E-00000001", 7)));

    JsonNode items = awaitLoads(approvalId, i -> allIn(i, 2, "APPLIED"));
    assertThat(allIn(items, 2, "APPLIED")).as(items.toString()).isTrue();
    JsonNode first =
        items.path(0).path("mfcId").asString().equals(m1) ? items.path(0) : items.path(1);
    assertThat(first.path("inventoryCommitId").asString()).isEqualTo("CMT-1");
    assertThat(first.path("lines")).hasSize(2);
    assertThat(first.path("attempts").asInt()).isEqualTo(1);
    String receiptId = first.path("receiptId").asString();

    // ConfirmReceipt: 서비스 토큰, Idempotency-Key = receiptId, TRF 없는 초기 반입 표기, 정상 수량만
    inventory.verify(
        postRequestedFor(urlPathEqualTo(RECEIPTS))
            .withoutHeader("Authorization") // 서비스 간 인증 없음(ADR-0026)
            .withHeader("Idempotency-Key", equalTo(receiptId))
            .withHeader("X-Source-System", equalTo("mfc-inbound-service"))
            .withRequestBody(matchingJsonPath("$.receiptId", equalTo(receiptId)))
            .withRequestBody(matchingJsonPath("$.trfId", equalTo("INITIAL-" + approvalId)))
            .withRequestBody(matchingJsonPath("$.departureId", equalTo("INITIAL")))
            .withRequestBody(matchingJsonPath("$.lines[0].sku", equalTo("B-00000002")))
            .withRequestBody(matchingJsonPath("$.lines[0].normalQty", equalTo("3")))
            .withRequestBody(matchingJsonPath("$.lines[0].quarantinedQty", equalTo("0")))
            .withRequestBody(matchingJsonPath("$.lines[1].normalQty", equalTo("10"))));

    // 서비스 조회(receiptId), 운영자 경로는 서비스 토큰 403, 내부 경로는 운영자 403
    Res internal = get(SERVICE_TOKEN, "/internal/initial-stock-loads/" + receiptId);
    assertThat(internal.status()).isEqualTo(200);
    assertThat(internal.body().path("state").asString()).isEqualTo("APPLIED");
    assertThat(internal.body().path("approvalId").asString()).isEqualTo(approvalId);
    assertThat(get(SERVICE_TOKEN, "/initial-stock-loads?approvalId=" + approvalId).status())
        .isEqualTo(403);
    assertThat(get(OPERATOR, "/internal/initial-stock-loads/" + receiptId).status()).isEqualTo(403);
    assertThat(get(SERVICE_TOKEN, "/internal/initial-stock-loads/RCV-none").status())
        .isEqualTo(404);
    assertThat(get(OPERATOR, "/initial-stock-loads?approvalId=AP-none").body().path("items"))
        .isEmpty();

    // 같은 이벤트 재전달 → 반입 기록이 늘지 않는다(inbox)
    kafka.send(TOPIC, approvalId, event).join();
    String marker = "AP-" + UUID.randomUUID();
    approve(marker, "INITIAL_STOCK", "APPROVED", List.of(line(m1, "E-00000003", 1)));
    awaitLoads(marker, i -> i.size() == 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM initial_stock_load WHERE approval_id = ?",
                Integer.class,
                approvalId))
        .isEqualTo(2);
  }

  @Test
  void inventoryOutageIsRetriedWithTheSameReceiptId() throws Exception {
    String m = mfc();
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(m)))
            .inScenario("outage")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse().withStatus(500)) // 응답 불명(UNKNOWN_RESULT). 503 은 HTTP 클라이언트가 자체 1회 재시도한다
            .willSetStateTo("up"));
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(m)))
            .inScenario("outage")
            .whenScenarioStateIs("up")
            .willReturn(
                json(201, Map.of("status", "APPLIED", "requestId", "r", "resultId", "CMT-R"))));
    String approvalId = "AP-" + UUID.randomUUID();
    approve(approvalId, "INITIAL_STOCK", "APPROVED", List.of(line(m, "E-00000001", 4)));

    JsonNode items = awaitLoads(approvalId, i -> allIn(i, 1, "APPLIED"));
    assertThat(allIn(items, 1, "APPLIED")).as(items.toString()).isTrue();
    assertThat(items.path(0).path("attempts").asInt()).isEqualTo(2);
    assertThat(items.path(0).path("inventoryCommitId").asString()).isEqualTo("CMT-R");
    assertThat(items.path(0).path("lastError").isNull()).isTrue();
    String receiptId = items.path(0).path("receiptId").asString();
    inventory.verify(
        2,
        postRequestedFor(urlPathEqualTo(RECEIPTS))
            .withHeader("Idempotency-Key", equalTo(receiptId))
            .withRequestBody(matchingJsonPath("$.receiptId", equalTo(receiptId))));
    // 두 번 다 같은 본문(receivedAt 포함) — inventory 가 receiptId 로 중복 계상을 막을 수 있다
    var bodies =
        inventory.findAll(
            postRequestedFor(urlPathEqualTo(RECEIPTS))
                .withHeader("Idempotency-Key", equalTo(receiptId)));
    assertThat(bodies.get(0).getBodyAsString()).isEqualTo(bodies.get(1).getBodyAsString());
  }

  @Test
  void inventoryRejectionAndInvalidApprovalAreRecordedAsRejected() throws Exception {
    // inventory 의 명시적 거절(4xx) → REJECTED, 재시도 안 함
    String m = mfc();
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(m)))
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":409,"
                            + "\"code\":\"RECEIPT_ALREADY_POSTED\",\"traceId\":\"t\"}")));
    String rejectedByInventory = "AP-" + UUID.randomUUID();
    approve(rejectedByInventory, "INITIAL_STOCK", "APPROVED", List.of(line(m, "E-00000001", 4)));
    JsonNode r1 = awaitLoads(rejectedByInventory, i -> allIn(i, 1, "REJECTED"));
    assertThat(r1.path(0).path("rejectReason").asString())
        .isEqualTo("INVENTORY_REJECTED:RECEIPT_ALREADY_POSTED (HTTP 409)");

    // 승인 내용 오류(수량 0·SKU 형식) → HTTP 호출 없이 REJECTED(INVALID: …)
    String bad = mfc();
    String invalid = "AP-" + UUID.randomUUID();
    approve(
        invalid,
        "INITIAL_STOCK",
        "APPROVED",
        List.of(line(bad, "E-00000001", 0), line(bad, "X-1", 2)));
    JsonNode r2 = awaitLoads(invalid, i -> allIn(i, 1, "REJECTED"));
    assertThat(r2.path(0).path("rejectReason").asString()).startsWith("INVALID: ");
    assertThat(r2.path(0).path("mfcId").asString()).isEqualTo(bad);
    assertThat(r2.path(0).path("lines")).isEmpty();

    // 다른 유형·반려 승인은 무시. 같은 파티션 키 표식 승인이 처리된 뒤 확인
    String other = "AP-" + UUID.randomUUID();
    approve(other, "CENTER_MASTER", "APPROVED", List.of(line(bad, "E-00000001", 1)));
    String declined = "AP-" + UUID.randomUUID();
    approve(declined, "INITIAL_STOCK", "REJECTED", List.of(line(bad, "E-00000001", 1)));
    String marker = "AP-" + UUID.randomUUID();
    approve(marker, "INITIAL_STOCK", "APPROVED", List.of(line(bad, "X", 1)));
    awaitLoads(marker, i -> i.size() == 1);
    Thread.sleep(500);
    assertThat(get(OPERATOR, "/initial-stock-loads?approvalId=" + other).body().path("items"))
        .isEmpty();
    assertThat(get(OPERATOR, "/initial-stock-loads?approvalId=" + declined).body().path("items"))
        .isEmpty();
    inventory.verify(
        0,
        postRequestedFor(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(bad))));
  }
}
