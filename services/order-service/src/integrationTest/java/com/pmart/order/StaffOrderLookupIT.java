package com.pmart.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.domain.ShippingAddress;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.LineRow;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 직원용 주문 검색·상세(2026-11-15, O-6, 계약 0.3.0): 역할 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 만, 기간 상한, 연락처 완전
 * 일치·목록 가림. 0.4.0: 상세는 배송지 가림, 원문은 사유 + audit-service 열람 기록(WireMock) 뒤에만 — audit 실패면 503·원문 없음.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=lookup-test"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "audit",
        baseUrlProperties = {"pmart.order.audit-base-url", "pmart.order.product-base-url"}))
class StaffOrderLookupIT extends PostgresKafkaContainerSupport {

  static final String AUDIT_PATH = "/api/v1/internal/pii-access-logs";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired OrderStore store;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.pmart.order.application.ProductCategories productCategories;

  @InjectWireMock("audit")
  WireMockServer audit;

  RestClient http;

  static final String CS = TestJwt.operator("cs-1", List.of("CS_AGENT"), List.of());
  static final String RIDER_OPS = TestJwt.operator("ro-1", List.of("RIDER_OPS"), List.of());
  static final String ADMIN = TestJwt.operator("ad-1", List.of("OPERATOR_ADMIN"), List.of());

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    audit.resetAll();
  }

  void auditRecords(String logId) {
    audit.stubFor(
        WireMock.post(urlPathEqualTo(AUDIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"piiAccessLogId\":\""
                            + logId
                            + "\",\"recordedAt\":\"2026-11-15T01:02:03Z\"}")));
  }

  ResponseEntity<String> reveal(String token, String orderId, String key, Object body) {
    return http.post()
        .uri("/orders/lookups/{id}/pii-reveals", orderId)
        .headers(h -> h.setBearerAuth(token))
        .header("Idempotency-Key", key)
        .header("X-Source-System", "backoffice-web")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  ResponseEntity<String> call(String token, String path) {
    return http.get()
        .uri(path)
        .headers(h -> h.setBearerAuth(token))
        .retrieve()
        .toEntity(String.class);
  }

  JsonNode ok(String token, String path) {
    var e = call(token, path);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(200);
    return mapper.readTree(e.getBody());
  }

  int status(String token, String path) {
    return call(token, path).getStatusCode().value();
  }

  /** 확정 주문 하나를 원장에 바로 넣는다(사가 없이 — 조회만 시험). */
  String order(
      String account, String mfc, String status, Instant confirmedAt, @Nullable String phone) {
    String orderId = "ORD-LK-" + UUID.randomUUID();
    var o =
        new OrderRow(
            orderId,
            account,
            "DRF-" + orderId,
            mfc,
            status,
            1,
            "SINGLE",
            null,
            null,
            null,
            "CANCEL_OOS_ONLY",
            "문 앞에 두세요",
            "ADDR-1",
            1,
            1,
            20_000,
            4_000,
            4_000,
            "STANDARD",
            100,
            24_100,
            false,
            1_500,
            1,
            "PA-" + orderId,
            "PG-" + orderId,
            24_100,
            "RSV-" + orderId,
            null,
            null,
            confirmedAt,
            null);
    List<LineRow> lines = new ArrayList<>();
    lines.add(
        new LineRow(
            orderId + "-L1",
            orderId,
            1,
            "CI-1",
            "E-00000001",
            "P-1",
            1,
            2,
            5_000,
            10_000,
            0,
            1_000,
            2,
            2,
            0,
            0,
            0,
            0,
            "사과",
            null,
            0));
    lines.add(
        new LineRow(
            orderId + "-L2",
            orderId,
            2,
            "CI-2",
            "E-00000002",
            "P-2",
            1,
            1,
            10_000,
            10_000,
            0,
            500,
            1,
            1,
            0,
            0,
            0,
            0,
            "우유",
            "1L",
            0));
    store.insert(o, lines);
    if (phone != null) {
      store.insertShipping(
          orderId,
          new ShippingAddress(
              "서울시 강남구 테헤란로 1",
              "101동 1001호",
              "06000",
              "홍길동",
              phone,
              "PASSWORD",
              "#1234",
              37.5,
              127.0,
              confirmedAt.minusSeconds(60)));
    }
    return orderId;
  }

  @Test
  void searchFiltersRangeCapAndMasking() {
    String mfc = "MFC-LK-" + UUID.randomUUID().toString().substring(0, 8);
    String account = "ACC-LK-" + UUID.randomUUID();
    var rnd = java.util.concurrent.ThreadLocalRandom.current();
    String tail = String.format("%04d", rnd.nextInt(10_000));
    String phone = String.format("010-%04d-", 5_000 + rnd.nextInt(5_000)) + tail;
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String older = order(account, mfc, "CONFIRMED", now.minus(Duration.ofDays(3)), phone);
    String newer = order(account, mfc, "PREPARING", now.minus(Duration.ofDays(1)), null);
    String other =
        order("ACC-LK-" + UUID.randomUUID(), mfc, "CONFIRMED", now.minus(Duration.ofDays(2)), null);
    String outOfRange = order(account, mfc, "CONFIRMED", now.minus(Duration.ofDays(40)), null);
    jdbc.update(
        "INSERT INTO order_fulfillment_issue (issue_id, source_event_id, order_id, mfc_id,"
            + " reservation_id, sku, blocked_qty, reason, state, blocked_at, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,'OPEN',?,?)",
        "FI-" + UUID.randomUUID(),
        UUID.randomUUID().toString(),
        older,
        mfc,
        "RSV-" + older,
        "E-00000001",
        1,
        "QUALITY_HOLD",
        Timestamp.from(now),
        Timestamp.from(now));

    String from = now.minus(Duration.ofDays(10)).toString();
    String to = now.plusSeconds(60).toString();

    // 센터 + 기간: 최근 확정 순, 기간 밖 제외
    JsonNode res =
        ok(CS, "/orders/lookups?mfcId=" + mfc + "&createdFrom=" + from + "&createdTo=" + to);
    List<String> ids = ids(res);
    assertThat(ids).containsExactly(newer, other, older);
    assertThat(res.path("page").path("hasNext").asBoolean()).isFalse();
    JsonNode olderRow = res.path("items").path(2);
    assertThat(olderRow.path("customerId").asString()).isEqualTo(account);
    assertThat(olderRow.path("itemCount").asInt()).isEqualTo(3);
    assertThat(olderRow.path("lineCount").asInt()).isEqualTo(2);
    assertThat(olderRow.path("totalAmount").asInt()).isEqualTo(24_100);
    assertThat(olderRow.path("openFulfillmentIssues").asInt()).isEqualTo(1);
    // 목록은 연락처를 가린다 — 원문·이름·주소는 응답에 없다
    assertThat(olderRow.path("receiverPhoneMasked").asString()).isEqualTo("010-****-" + tail);
    assertThat(res.toString()).doesNotContain(phone).doesNotContain("홍길동").doesNotContain("테헤란로");
    assertThat(res.path("items").path(0).path("receiverPhoneMasked").isNull()).isTrue();

    // 상태
    assertThat(
            ids(
                ok(
                    RIDER_OPS,
                    "/orders/lookups?mfcId="
                        + mfc
                        + "&status=PREPARING&createdFrom="
                        + from
                        + "&createdTo="
                        + to)))
        .containsExactly(newer);

    // 쪽 나눔
    JsonNode p0 =
        ok(
            ADMIN,
            "/orders/lookups?mfcId=" + mfc + "&size=2&createdFrom=" + from + "&createdTo=" + to);
    assertThat(ids(p0)).containsExactly(newer, other);
    assertThat(p0.path("page").path("hasNext").asBoolean()).isTrue();
    JsonNode p1 =
        ok(
            ADMIN,
            "/orders/lookups?mfcId="
                + mfc
                + "&size=2&page=1&createdFrom="
                + from
                + "&createdTo="
                + to);
    assertThat(ids(p1)).containsExactly(older);
    assertThat(p1.path("page").path("hasNext").asBoolean()).isFalse();

    // 고객 ID: 기간 없이 된다(기간 밖 주문 포함)
    assertThat(ids(ok(CS, "/orders/lookups?customerId=" + account)))
        .containsExactly(newer, older, outOfRange);
    // 주문 ID 완전 일치
    assertThat(ids(ok(CS, "/orders/lookups?orderId=" + other))).containsExactly(other);
    assertThat(ids(ok(CS, "/orders/lookups?orderId=" + other.substring(0, 12)))).isEmpty();
    // 화면용 주문번호 완전 일치(2026-11-16) — 기간 없이, 하이픈 허용, 8자리 아니면 400
    String otherNumber =
        jdbc.queryForObject(
            "SELECT order_number FROM customer_order WHERE order_id = ?", String.class, other);
    assertThat(otherNumber).matches("[1-9][0-9]{7}");
    assertThat(ids(ok(CS, "/orders/lookups?orderNumber=" + otherNumber))).containsExactly(other);
    assertThat(
            ids(
                ok(
                    CS,
                    "/orders/lookups?orderNumber="
                        + otherNumber.substring(0, 4)
                        + "-"
                        + otherNumber.substring(4))))
        .containsExactly(other);
    assertThat(status(CS, "/orders/lookups?orderNumber=1234")).isEqualTo(400);

    // 연락처 완전 일치(하이픈 무시) — 끝자리만으로는 찾지 못한다
    String digits = phone.replace("-", "");
    assertThat(
            ids(
                ok(
                    CS,
                    "/orders/lookups?receiverPhone="
                        + digits
                        + "&createdFrom="
                        + from
                        + "&createdTo="
                        + to)))
        .containsExactly(older);
    assertThat(
            ids(
                ok(
                    CS,
                    "/orders/lookups?receiverPhone="
                        + phone
                        + "&createdFrom="
                        + from
                        + "&createdTo="
                        + to)))
        .containsExactly(older);
    assertThat(
            status(
                CS,
                "/orders/lookups?receiverPhone="
                    + tail
                    + "&createdFrom="
                    + from
                    + "&createdTo="
                    + to))
        .isEqualTo(400);

    // 기간 상한: 키 없이 기간 없음·31일 초과·순서 오류 → 400
    assertThat(status(CS, "/orders/lookups?mfcId=" + mfc)).isEqualTo(400);
    assertThat(
            status(
                CS,
                "/orders/lookups?mfcId="
                    + mfc
                    + "&createdFrom="
                    + now.minus(Duration.ofDays(32))
                    + "&createdTo="
                    + now))
        .isEqualTo(400);
    assertThat(
            status(
                CS, "/orders/lookups?mfcId=" + mfc + "&createdFrom=" + to + "&createdTo=" + from))
        .isEqualTo(400);
    // 쪽 크기 상한 50
    assertThat(
            status(
                CS,
                "/orders/lookups?mfcId="
                    + mfc
                    + "&size=51&createdFrom="
                    + from
                    + "&createdTo="
                    + to))
        .isEqualTo(400);
  }

  @Test
  void detailShowsFullShippingAndOrderOwnedFacts() {
    String mfc = "MFC-LK-" + UUID.randomUUID().toString().substring(0, 8);
    String account = "ACC-LK-" + UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String orderId = order(account, mfc, "CONFIRMED", at, "010-2222-3333");

    JsonNode d = ok(CS, "/orders/lookups/" + orderId);
    assertThat(d.path("orderId").asString()).isEqualTo(orderId);
    assertThat(d.path("customerId").asString()).isEqualTo(account);
    assertThat(d.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(d.path("mfcId").asString()).isEqualTo(mfc);
    // 배송 요청사항은 가림(ADR-0062, 0.5.0) — 있으면 deliveryNoteMasked
    assertThat(d.path("deliveryNote").isMissingNode() || d.path("deliveryNote").isNull()).isTrue();
    assertThat(d.path("deliveryNoteMasked").asBoolean()).isTrue();
    assertThat(d.path("amounts").path("totalAmount").asInt()).isEqualTo(24_100);
    assertThat(d.path("amounts").path("approvedAmount").asInt()).isEqualTo(24_100);
    assertThat(d.path("amounts").path("deliveryTip").asInt()).isEqualTo(4_000);
    assertThat(d.path("payment").path("paymentAttemptId").asString()).isEqualTo("PA-" + orderId);
    assertThat(d.path("lines")).hasSize(2);
    assertThat(d.path("lines").path(0).path("productName").asString()).isEqualTo("사과");
    assertThat(d.path("lines").path(0).path("originalQty").asInt()).isEqualTo(2);
    // 0.4.0: 배송지는 가린다 — 도로명·우편번호·출입 방법만 그대로
    assertThat(d.path("shippingAddressMasked").asBoolean()).isTrue();
    JsonNode s = d.path("shippingAddress");
    assertThat(s.path("receiverName").asString()).isEqualTo("홍*동");
    assertThat(s.path("receiverPhone").asString()).isEqualTo("010-****-3333");
    assertThat(s.path("roadAddress").asString()).isEqualTo("서울시 강남구 테헤란로 1");
    assertThat(s.path("postalCode").asString()).isEqualTo("06000");
    assertThat(s.path("entranceMethod").asString()).isEqualTo("PASSWORD");
    assertThat(s.path("detailAddress").asString()).isEqualTo("***");
    assertThat(s.path("entranceNote").isMissingNode() || s.path("entranceNote").isNull()).isTrue();
    assertThat(s.path("geo").isMissingNode() || s.path("geo").isNull()).isTrue();
    assertThat(d.toString())
        .doesNotContain("홍길동")
        .doesNotContain("2222")
        .doesNotContain("1001호")
        .doesNotContain("#1234");
    assertThat(d.path("fulfillmentIssues").isArray()).isTrue();
    // 가린 조회는 기록하지 않는다
    audit.verify(0, postRequestedFor(urlPathEqualTo(AUDIT_PATH)));

    assertThat(ok(RIDER_OPS, "/orders/lookups/" + orderId).path("orderId").asString())
        .isEqualTo(orderId);
    assertThat(status(ADMIN, "/orders/lookups/ORD-LK-NONE-" + UUID.randomUUID())).isEqualTo(404);
  }

  @Test
  void customersAndOtherStaffRolesAreForbidden() {
    String mfc = "MFC-LK-" + UUID.randomUUID().toString().substring(0, 8);
    String account = "ACC-LK-" + UUID.randomUUID();
    String orderId = order(account, mfc, "CONFIRMED", Instant.now(), "010-4444-5555");

    // 주문한 고객 본인도 직원 경로는 안 된다
    String customer = TestJwt.customer(account);
    assertThat(status(customer, "/orders/lookups?orderId=" + orderId)).isEqualTo(403);
    assertThat(status(customer, "/orders/lookups/" + orderId)).isEqualTo(403);
    // 센터 운영자·작업자(그 센터라도)·역할 없는 직원
    for (List<String> roles :
        List.of(List.of("MFC_SUPERVISOR"), List.of("MFC_WORKER"), List.<String>of())) {
      String op = TestJwt.operator("op-x", roles, List.of(mfc));
      assertThat(status(op, "/orders/lookups?orderId=" + orderId))
          .as(roles.toString())
          .isEqualTo(403);
      assertThat(status(op, "/orders/lookups/" + orderId)).as(roles.toString()).isEqualTo(403);
    }
    // 라이더
    assertThat(status(TestJwt.rider("R-1"), "/orders/lookups/" + orderId)).isEqualTo(403);
    // 고객 경로는 그대로(본인 주문)
    assertThat(status(customer, "/orders/" + orderId)).isEqualTo(200);

    // 원문 열람도 같은 역할만 — 기록 요청조차 하지 않는다
    auditRecords("PAL-X");
    var reason = Map.of("reason", "고객 문의");
    assertThat(reveal(customer, orderId, "k-c", reason).getStatusCode().value()).isEqualTo(403);
    assertThat(reveal(TestJwt.rider("R-1"), orderId, "k-r", reason).getStatusCode().value())
        .isEqualTo(403);
    String supervisor = TestJwt.operator("op-x", List.of("MFC_SUPERVISOR"), List.of(mfc));
    assertThat(reveal(supervisor, orderId, "k-m", reason).getStatusCode().value()).isEqualTo(403);
    audit.verify(0, postRequestedFor(urlPathEqualTo(AUDIT_PATH)));
  }

  /** ADR-0065: 상담원이 다시 배송 실제 집 주소를 기록 → 내부 조회만 주소를 준다(직원 응답엔 없음), 같은 키는 같은 ID, 주문 사본은 그대로. */
  @Test
  void staffRecordsRedeliveryDestinationAndInternalReadsIt() {
    String mfc = "MFC-RD-" + UUID.randomUUID().toString().substring(0, 8);
    String orderId =
        order("ACC-RD-" + UUID.randomUUID(), mfc, "COMPLETED", Instant.now(), "010-4444-5555");
    Map<String, Object> body =
        Map.of(
            "deliveryId", "DLV-RD-1",
            "roadAddress", "서울 강남구 테헤란로 2",
            "detailAddress", "202동 1501호",
            "reason", "고객 통화 — 동 호수 잘못 입력");
    ResponseEntity<String> r = record(CS, orderId, "rk-1", body);
    assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(201);
    JsonNode d = mapper.readTree(r.getBody());
    String id = d.path("destinationId").asString();
    assertThat(id).startsWith("RDD-");
    assertThat(d.has("roadAddress") && !d.path("roadAddress").isNull()).isFalse(); // 직원 응답엔 주소 없음
    assertThat(
            mapper
                .readTree(record(CS, orderId, "rk-1", body).getBody())
                .path("destinationId")
                .asString())
        .isEqualTo(id);
    // 사유 없음 400, 고객 403, 없는 주문 404
    assertThat(
            record(CS, orderId, "rk-2", Map.of("deliveryId", "D", "roadAddress", "x"))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(record(TestJwt.customer("ACC-X"), orderId, "rk-3", body).getStatusCode().value())
        .isEqualTo(403);
    assertThat(record(CS, "ORD-none", "rk-4", body).getStatusCode().value()).isEqualTo(404);

    JsonNode internal =
        ok(
            TestJwt.service("delivery-dispatch-service"),
            "/internal/orders/" + orderId + "/redelivery-destinations/" + id);
    assertThat(internal.path("roadAddress").asString()).isEqualTo("서울 강남구 테헤란로 2");
    assertThat(internal.path("detailAddress").asString()).isEqualTo("202동 1501호");
    assertThat(internal.path("deliveryId").asString()).isEqualTo("DLV-RD-1");
    assertThat(
            status(
                TestJwt.service("delivery-dispatch-service"),
                "/internal/orders/ORD-other/redelivery-destinations/" + id))
        .isEqualTo(404);
  }

  ResponseEntity<String> record(String token, String orderId, String key, Object body) {
    return http.post()
        .uri("/orders/lookups/{id}/redelivery-destinations", orderId)
        .headers(h -> h.setBearerAuth(token))
        .header("Idempotency-Key", key)
        .header("X-Source-System", "backoffice-web")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  void revealRecordsAuditThenReturnsOriginal() {
    String mfc = "MFC-LK-" + UUID.randomUUID().toString().substring(0, 8);
    String account = "ACC-LK-" + UUID.randomUUID();
    String orderId = order(account, mfc, "CONFIRMED", Instant.now(), "010-2222-3333");
    auditRecords("PAL-1");

    // 사유 없음·짧음·너무 김 → 400, 기록 요청 없음
    assertThat(reveal(CS, orderId, "k0", Map.of()).getStatusCode().value()).isEqualTo(400);
    assertThat(reveal(CS, orderId, "k0", Map.of("reason", " 가 ")).getStatusCode().value())
        .isEqualTo(400);
    assertThat(reveal(CS, orderId, "k0", Map.of("reason", "가".repeat(201))).getStatusCode().value())
        .isEqualTo(400);
    audit.verify(0, postRequestedFor(urlPathEqualTo(AUDIT_PATH)));

    // 전체 항목
    String key = "k-" + UUID.randomUUID();
    var reason = Map.of("reason", "고객 문의 — 배송지 확인");
    var e = reveal(CS, orderId, key, reason);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(200);
    JsonNode r = mapper.readTree(e.getBody());
    assertThat(r.path("orderId").asString()).isEqualTo(orderId);
    assertThat(r.path("piiAccessLogId").asString()).isEqualTo("PAL-1");
    assertThat(r.path("fields").toString())
        .isEqualTo("[\"NAME\",\"PHONE\",\"ADDRESS\",\"ENTRANCE\"]");
    JsonNode s = r.path("shippingAddress");
    assertThat(s.path("receiverName").asString()).isEqualTo("홍길동");
    assertThat(s.path("receiverPhone").asString()).isEqualTo("010-2222-3333");
    assertThat(s.path("detailAddress").asString()).isEqualTo("101동 1001호");
    assertThat(s.path("entranceNote").asString()).isEqualTo("#1234");
    assertThat(r.path("deliveryNote").asString())
        .as("ENTRANCE 를 열면 배송 요청사항도")
        .isEqualTo("문 앞에 두세요");
    assertThat(s.path("geo").path("lat").asDouble()).isEqualTo(37.5);

    // audit 에 간 본문: 행위자·역할·고객·주문·항목·사유(원문 개인정보 없음). 키는 직원·주문·클라이언트 키로 정한 값
    audit.verify(
        1,
        postRequestedFor(urlPathEqualTo(AUDIT_PATH))
            .withHeader("Idempotency-Key", WireMock.matching("order-pii-reveal:[0-9a-f]{64}"))
            .withHeader("X-Source-System", equalTo("order-service"))
            .withRequestBody(matchingJsonPath("$.actor.actorId", equalTo("cs-1")))
            .withRequestBody(matchingJsonPath("$.actor.actorType", equalTo("OPERATOR")))
            .withRequestBody(matchingJsonPath("$.actor.roles[0]", equalTo("CS_AGENT")))
            .withRequestBody(matchingJsonPath("$.subjectType", equalTo("CUSTOMER")))
            .withRequestBody(matchingJsonPath("$.subjectRef", equalTo(account)))
            .withRequestBody(matchingJsonPath("$.accessType", equalTo("VIEW")))
            .withRequestBody(matchingJsonPath("$.caseRef", equalTo("ORDER:" + orderId)))
            .withRequestBody(matchingJsonPath("$.purpose", equalTo("고객 문의 — 배송지 확인")))
            .withRequestBody(matchingJsonPath("$.fields[3]", equalTo("ENTRANCE"))));
    var first = audit.getAllServeEvents().get(0).getRequest();
    assertThat(first.getBodyAsString())
        .doesNotContain("홍길동")
        .doesNotContain("2222")
        .doesNotContain("1001호");
    String firstKey = first.getHeader("Idempotency-Key");

    // 같은 키 재시도 → 같은 audit 키(같은 기록으로 이어짐)
    assertThat(reveal(CS, orderId, key, reason).getStatusCode().value()).isEqualTo(200);
    assertThat(audit.getAllServeEvents().get(0).getRequest().getHeader("Idempotency-Key"))
        .isEqualTo(firstKey);
    // 다른 직원이 같은 키 값을 써도 audit 키는 다르다
    assertThat(reveal(ADMIN, orderId, key, reason).getStatusCode().value()).isEqualTo(200);
    assertThat(audit.getAllServeEvents().get(0).getRequest().getHeader("Idempotency-Key"))
        .isNotEqualTo(firstKey);

    // 항목 일부만: 연락처만 원문, 나머지는 가린 값
    var phoneOnly =
        reveal(
            RIDER_OPS,
            orderId,
            "k-" + UUID.randomUUID(),
            Map.of("reason", "라이더 연락 불가", "fields", List.of("PHONE")));
    assertThat(phoneOnly.getStatusCode().value()).as(phoneOnly.getBody()).isEqualTo(200);
    JsonNode p = mapper.readTree(phoneOnly.getBody());
    assertThat(p.path("fields").toString()).isEqualTo("[\"PHONE\"]");
    assertThat(p.path("shippingAddress").path("receiverPhone").asString())
        .isEqualTo("010-2222-3333");
    assertThat(p.path("shippingAddress").path("receiverName").asString()).isEqualTo("홍*동");
    assertThat(p.path("shippingAddress").path("detailAddress").asString()).isEqualTo("***");
    assertThat(p.path("deliveryNote").isMissingNode() || p.path("deliveryNote").isNull())
        .as("PHONE 만 열면 배송 요청사항은 그대로 가림")
        .isTrue();

    // audit 의 409(같은 키·다른 사유)는 그대로, 원문 없음
    audit.stubFor(
        WireMock.post(urlPathEqualTo(AUDIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"status\":409,\"code\":\"IDEMPOTENCY_CONFLICT\",\"detail\":\"x\"}")));
    var conflict = reveal(CS, orderId, key, Map.of("reason", "다른 사유"));
    assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    assertThat(conflict.getBody()).doesNotContain("2222").doesNotContain("홍길동");

    // 없는 주문 404, 사본 없는 주문 404 — 기록 요청 없음
    audit.resetRequests();
    var none = reveal(ADMIN, "ORD-LK-NONE-" + UUID.randomUUID(), "k-n", Map.of("reason", "확인"));
    assertThat(none.getStatusCode().value()).isEqualTo(404);
    String noCopy = order(account, mfc, "CONFIRMED", Instant.now(), null);
    var nc = reveal(ADMIN, noCopy, "k-nc", Map.of("reason", "확인"));
    assertThat(nc.getStatusCode().value()).isEqualTo(404);
    assertThat(nc.getBody()).contains("SHIPPING_COPY_NOT_FOUND");
    audit.verify(0, postRequestedFor(urlPathEqualTo(AUDIT_PATH)));
  }

  @Test
  void auditDownMeans503AndNoOriginal() {
    String mfc = "MFC-LK-" + UUID.randomUUID().toString().substring(0, 8);
    String orderId =
        order("ACC-LK-" + UUID.randomUUID(), mfc, "CONFIRMED", Instant.now(), "010-7777-8888");
    var reason = Map.of("reason", "고객 문의");

    // audit 503 → DEPENDENCY_UNAVAILABLE
    audit.stubFor(
        WireMock.post(urlPathEqualTo(AUDIT_PATH)).willReturn(aResponse().withStatus(503)));
    var down = reveal(CS, orderId, "k-d1", reason);
    assertThat(down.getStatusCode().value()).isEqualTo(503);
    assertThat(down.getBody())
        .contains("DEPENDENCY_UNAVAILABLE")
        .doesNotContain("7777")
        .doesNotContain("홍길동");

    // audit 500 → 결과 불명(UNKNOWN_RESULT, 503) — 원문 없음
    audit.stubFor(
        WireMock.post(urlPathEqualTo(AUDIT_PATH)).willReturn(aResponse().withStatus(500)));
    var unknown = reveal(CS, orderId, "k-d2", reason);
    assertThat(unknown.getStatusCode().value()).isEqualTo(503);
    assertThat(unknown.getBody()).contains("UNKNOWN_RESULT").doesNotContain("7777");

    // audit 연결 끊김 → 503, 원문 없음
    audit.stubFor(
        WireMock.post(urlPathEqualTo(AUDIT_PATH))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    var reset = reveal(CS, orderId, "k-d3", reason);
    assertThat(reset.getStatusCode().value()).isEqualTo(503);
    assertThat(reset.getBody()).doesNotContain("7777").doesNotContain("홍길동");
  }

  // ---------------------------------------------------------------- O-8 기간 집계(0.13.0)

  /** 집계용 주문행: SKU·상품·주문 수량·줄 금액·할인 분담·출고 전 취소 수량. */
  record M(String sku, String productId, int qty, long lineAmount, long discount, int cancelled) {}

  String metricsOrder(
      String account, String mfc, String status, Instant confirmedAt, long approved, M... ms) {
    String orderId = "ORD-MT-" + UUID.randomUUID();
    var o =
        new OrderRow(
            orderId,
            account,
            "DRF-" + orderId,
            mfc,
            status,
            1,
            "SINGLE",
            null,
            null,
            null,
            "CANCEL_OOS_ONLY",
            null,
            "ADDR-1",
            1,
            1,
            approved,
            0,
            0,
            "STANDARD",
            0,
            approved,
            false,
            1_000,
            1,
            "PA-" + orderId,
            "PG-" + orderId,
            approved,
            "RSV-" + orderId,
            null,
            null,
            confirmedAt,
            null);
    List<LineRow> lines = new ArrayList<>();
    int n = 1;
    for (M m : ms) {
      lines.add(
          new LineRow(
              orderId + "-L" + n,
              orderId,
              n,
              null,
              m.sku(),
              m.productId(),
              1,
              m.qty(),
              m.lineAmount() / m.qty(),
              m.lineAmount(),
              m.discount(),
              100,
              m.qty(),
              m.qty() - m.cancelled(),
              0,
              m.cancelled(),
              0,
              0,
              "상품" + n,
              null,
              0));
      n++;
    }
    store.insert(o, lines);
    return orderId;
  }

  /**
   * O-8: OPERATOR_ADMIN 만, 확정 시각의 한국 날짜(from·to 포함), 상태별 수·승인 금액 합(환불 안 뺌), SKU별 남은 수량·금액(할인 분담 같은
   * 비율, 원 미만 버림) 상위 N. 글자 그대로의 /orders/metrics/... 와 /orders/{orderId} 가 둘 다 맞게 간다.
   */
  @Test
  void periodMetricsUseKoreanConfirmedDateAndOperatorAdminOnly() {
    String mfc = "MFC-MT-" + UUID.randomUUID().toString().substring(0, 8);
    String account = "ACC-MT-" + UUID.randomUUID();
    String x = "X-" + UUID.randomUUID().toString().substring(0, 8);
    String y = "Y-" + UUID.randomUUID().toString().substring(0, 8);
    String z = "Z-" + UUID.randomUUID().toString().substring(0, 8);
    // 2019-07-14 00:30 KST — 그날
    String a =
        metricsOrder(
            account,
            mfc,
            "CONFIRMED",
            Instant.parse("2019-07-13T15:30:00Z"),
            30_000,
            new M(x, "P-X", 3, 9_000, 300, 1), // 남은 2 → (9000-300)×2÷3 = 5800
            new M(y, "P-Y", 1, 2_000, 0, 0)); // 2000
    // 2019-07-14 23:59:59 KST — 그날, 확정 뒤 취소(승인 금액은 그대로 센다)
    metricsOrder(
        account,
        mfc,
        "CANCELLED",
        Instant.parse("2019-07-14T14:59:59Z"),
        12_000,
        new M(x, "P-X", 2, 6_000, 0, 2), // 전부 출고 전 취소 → 0
        new M(z, "P-Z", 1, 7_001, 1_000, 0)); // 6001
    // 2019-07-13 23:59:59 KST — 전날
    metricsOrder(
        account,
        mfc,
        "CONFIRMED",
        Instant.parse("2019-07-13T14:59:59Z"),
        99_999,
        new M(y, "P-Y", 5, 50_000, 0, 0));
    // 다른 센터, 그날
    metricsOrder(
        account,
        "MFC-MT-OTHER-" + UUID.randomUUID().toString().substring(0, 4),
        "PREPARING",
        Instant.parse("2019-07-14T03:00:00Z"),
        5_000,
        new M(x, "P-X", 1, 1_000, 0, 0));

    String day = "from=2019-07-14&to=2019-07-14";
    JsonNode sum = ok(ADMIN, "/orders/metrics/summary?" + day + "&mfcId=" + mfc);
    assertThat(sum.path("orderCount").asInt()).isEqualTo(2);
    assertThat(sum.path("approvedAmount").asLong()).isEqualTo(42_000);
    assertThat(sum.path("approvedOrderCount").asInt()).isEqualTo(2);
    assertThat(sum.path("mfcId").asString()).isEqualTo(mfc);
    assertThat(sum.path("statusCounts")).hasSize(2);
    assertThat(sum.path("statusCounts").get(0).path("status").asString()).isEqualTo("CANCELLED");
    assertThat(sum.path("statusCounts").get(0).path("count").asInt()).isEqualTo(1);
    assertThat(sum.path("statusCounts").get(1).path("status").asString()).isEqualTo("CONFIRMED");
    // 센터를 안 주면 모든 센터(그날은 이 시험의 주문뿐)
    JsonNode all = ok(ADMIN, "/orders/metrics/summary?" + day);
    assertThat(all.path("orderCount").asInt()).isEqualTo(3);
    assertThat(all.path("approvedAmount").asLong()).isEqualTo(47_000);
    assertThat(all.path("mfcId").isNull() || all.path("mfcId").isMissingNode()).isTrue();

    JsonNode top = ok(ADMIN, "/orders/metrics/top-products?" + day + "&mfcId=" + mfc);
    assertThat(top.path("limit").asInt()).isEqualTo(10);
    JsonNode items = top.path("items");
    assertThat(items).hasSize(3);
    assertThat(items.get(0).path("sku").asString()).isEqualTo(z);
    assertThat(items.get(0).path("productId").asString()).isEqualTo("P-Z");
    assertThat(items.get(0).path("qty").asInt()).isEqualTo(1);
    assertThat(items.get(0).path("amount").asLong()).isEqualTo(6_001);
    assertThat(items.get(1).path("sku").asString()).isEqualTo(x);
    assertThat(items.get(1).path("qty").asInt()).isEqualTo(2);
    assertThat(items.get(1).path("amount").asLong()).isEqualTo(5_800);
    assertThat(items.get(2).path("sku").asString()).isEqualTo(y);
    assertThat(items.get(2).path("amount").asLong()).isEqualTo(2_000);
    assertThat(
            ok(ADMIN, "/orders/metrics/top-products?" + day + "&mfcId=" + mfc + "&limit=1")
                .path("items"))
        .hasSize(1);
    // 이틀(전날 포함) — 전날 주문의 Y 가 더해져 맨 위
    JsonNode two =
        ok(ADMIN, "/orders/metrics/top-products?from=2019-07-13&to=2019-07-14&mfcId=" + mfc)
            .path("items");
    assertThat(two.get(0).path("sku").asString()).isEqualTo(y);
    assertThat(two.get(0).path("qty").asInt()).isEqualTo(6);
    assertThat(two.get(0).path("amount").asLong()).isEqualTo(52_000);

    // 권한·기간
    assertThat(status(CS, "/orders/metrics/summary?" + day)).isEqualTo(403);
    assertThat(status(RIDER_OPS, "/orders/metrics/top-products?" + day)).isEqualTo(403);
    assertThat(status(TestJwt.customer(account), "/orders/metrics/summary?" + day)).isEqualTo(403);
    assertThat(status(ADMIN, "/orders/metrics/summary?from=2019-07-15&to=2019-07-14"))
        .isEqualTo(400);
    assertThat(status(ADMIN, "/orders/metrics/summary?from=2019-01-01&to=2019-04-04"))
        .isEqualTo(400); // 94일
    assertThat(status(ADMIN, "/orders/metrics/summary?from=2019-01-01&to=2019-04-03"))
        .isEqualTo(200); // 93일
    assertThat(status(ADMIN, "/orders/metrics/summary?to=2019-07-14")).isEqualTo(400);
    assertThat(status(ADMIN, "/orders/metrics/top-products?" + day + "&limit=51")).isEqualTo(400);

    // 경로가 겹치지 않는다: 주문 상세는 그대로 주문 상세
    JsonNode detail = ok(TestJwt.customer(account), "/orders/" + a);
    assertThat(detail.path("orderId").asString()).isEqualTo(a);
  }

  /**
   * 0.20.0 O-11(2026-11-23 사용자): 센터 관리자는 자기 센터만(mfcId 필수 400, 다른 센터 403), 관리자는 모든 센터. 추이 HOUR·DAY·
   * WEEKDAY_HOUR(한국 시각), sku·카테고리 거르기(하위 포함 — product 지금 값), top-products 줄의 카테고리.
   */
  @Test
  void centerSupervisorSeesOwnCenterMetricsAndSeries() {
    String mfc = "MFC-SR-" + UUID.randomUUID().toString().substring(0, 8);
    String account = "ACC-SR-" + UUID.randomUUID();
    String x = "X-" + UUID.randomUUID().toString().substring(0, 8);
    String y = "Y-" + UUID.randomUUID().toString().substring(0, 8);
    String px = "PRD-X-" + x;
    String py = "PRD-Y-" + y;
    audit.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/categories/tree"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"version\":1,\"nodes\":["
                            + "{\"categoryId\":\"c1\",\"name\":\"라면\",\"path\":\"라면\"},"
                            + "{\"categoryId\":\"c1-a\",\"parentId\":\"c1\",\"name\":\"봉지\",\"path\":\"라면 > 봉지\"},"
                            + "{\"categoryId\":\"c2\",\"name\":\"우유\",\"path\":\"우유\"}]}")));
    audit.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/products"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"productId\":\""
                            + px
                            + "\",\"primaryCategoryId\":\"c1-a\"},{\"productId\":\""
                            + py
                            + "\",\"primaryCategoryId\":\"c2\"}],\"page\":{\"nextCursor\":null}}")));
    productCategories.invalidate(); // 앞 시험이 빈 사본을 읽어 두었을 수 있다
    // 2019-08-05(월) 10:20·10:40 KST, 11:05 KST, 2019-08-06(화) 10:00 KST — 다른 센터 한 건
    metricsOrder(
        account,
        mfc,
        "CONFIRMED",
        Instant.parse("2019-08-05T01:20:00Z"),
        10_000,
        new M(x, px, 2, 10_000, 0, 0));
    metricsOrder(
        account,
        mfc,
        "CONFIRMED",
        Instant.parse("2019-08-05T01:40:00Z"),
        5_000,
        new M(y, py, 1, 5_000, 0, 0));
    metricsOrder(
        account,
        mfc,
        "CONFIRMED",
        Instant.parse("2019-08-05T02:05:00Z"),
        3_000,
        new M(x, px, 1, 3_000, 0, 0));
    metricsOrder(
        account,
        mfc,
        "CONFIRMED",
        Instant.parse("2019-08-06T01:00:00Z"),
        7_000,
        new M(x, px, 1, 4_000, 0, 0),
        new M(y, py, 1, 3_000, 0, 0));
    metricsOrder(
        account,
        "MFC-OTHER-" + mfc,
        "CONFIRMED",
        Instant.parse("2019-08-05T01:30:00Z"),
        99_000,
        new M(x, px, 9, 99_000, 0, 0));

    String sup = TestJwt.operator("sup-1", List.of("MFC_SUPERVISOR"), List.of(mfc));
    String days = "from=2019-08-05&to=2019-08-06";
    // 권한: mfcId 없음 400, 다른 센터 403, 자기 센터 200
    assertThat(status(sup, "/orders/metrics/summary?" + days)).isEqualTo(400);
    assertThat(status(sup, "/orders/metrics/summary?" + days + "&mfcId=MFC-OTHER-" + mfc))
        .isEqualTo(403);
    assertThat(status(sup, "/orders/metrics/series?" + days + "&bucket=DAY")).isEqualTo(400);
    assertThat(status(CS, "/orders/metrics/series?" + days + "&bucket=DAY&mfcId=" + mfc))
        .isEqualTo(403);
    JsonNode sum = ok(sup, "/orders/metrics/summary?" + days + "&mfcId=" + mfc);
    assertThat(sum.path("orderCount").asInt()).isEqualTo(4);
    assertThat(sum.path("approvedAmount").asLong()).isEqualTo(25_000);

    // 상위 상품 + 카테고리, 카테고리 거르기(c1 → 하위 c1-a 의 X 만)
    JsonNode top = ok(sup, "/orders/metrics/top-products?" + days + "&mfcId=" + mfc).path("items");
    assertThat(top.get(0).path("sku").asString()).isEqualTo(x);
    assertThat(top.get(0).path("categoryId").asString()).isEqualTo("c1-a");
    assertThat(top.get(0).path("categoryName").asString()).isEqualTo("라면 > 봉지");
    JsonNode ramen =
        ok(sup, "/orders/metrics/top-products?" + days + "&mfcId=" + mfc + "&categoryId=c1")
            .path("items");
    assertThat(ramen.findValuesAsString("sku")).containsExactly(x);

    // 시간별: 10시 2건(15,000), 11시 1건, 다음 날 10시 1건 — 0건 칸 없음
    JsonNode hours =
        ok(sup, "/orders/metrics/series?" + days + "&bucket=HOUR&mfcId=" + mfc).path("items");
    assertThat(hours.size()).isEqualTo(3);
    assertThat(hours.get(0).path("start").asString()).startsWith("2019-08-05T10:00");
    assertThat(hours.get(0).path("orderCount").asInt()).isEqualTo(2);
    assertThat(hours.get(0).path("approvedAmount").asLong()).isEqualTo(15_000);
    assertThat(hours.get(0).path("qty").asInt()).isEqualTo(3);
    // 날짜별 + sku 거르기: 그 상품 줄이 든 주문·줄 금액만, approvedAmount 없음
    JsonNode dayX =
        ok(sup, "/orders/metrics/series?" + days + "&bucket=DAY&mfcId=" + mfc + "&sku=" + x)
            .path("items");
    assertThat(dayX.size()).isEqualTo(2);
    assertThat(dayX.get(0).path("orderCount").asInt()).isEqualTo(2);
    assertThat(dayX.get(0).path("lineAmount").asLong()).isEqualTo(13_000);
    assertThat(dayX.get(0).path("approvedAmount").isNull()).isTrue();
    assertThat(dayX.get(1).path("lineAmount").asLong()).isEqualTo(4_000);
    // 카테고리 거르기(c2 → Y)
    JsonNode milk =
        ok(sup, "/orders/metrics/series?" + days + "&bucket=DAY&mfcId=" + mfc + "&categoryId=c2")
            .path("items");
    assertThat(milk.size()).isEqualTo(2);
    assertThat(milk.get(0).path("orderCount").asInt()).isEqualTo(1);
    // 요일×시간대: 8일(월 두 번) — 월 10시 2건 → 평균 1.00
    JsonNode wh =
        ok(
                sup,
                "/orders/metrics/series?from=2019-08-05&to=2019-08-12&bucket=WEEKDAY_HOUR&mfcId="
                    + mfc)
            .path("items");
    JsonNode mon10 = wh.get(0);
    assertThat(mon10.path("weekday").asInt()).isEqualTo(1);
    assertThat(mon10.path("hour").asInt()).isEqualTo(10);
    assertThat(mon10.path("days").asInt()).isEqualTo(2);
    assertThat(mon10.path("avgOrderCount").asDouble()).isEqualTo(1.0);
    // 기간: HOUR 94일 400, WEEKDAY_HOUR 366일 200 / 367일 400
    assertThat(
            status(
                sup,
                "/orders/metrics/series?from=2019-01-01&to=2019-04-04&bucket=HOUR&mfcId=" + mfc))
        .isEqualTo(400);
    assertThat(
            status(
                sup,
                "/orders/metrics/series?from=2019-01-01&to=2020-01-01&bucket=WEEKDAY_HOUR&mfcId="
                    + mfc))
        .isEqualTo(200);
    assertThat(
            status(
                sup,
                "/orders/metrics/series?from=2019-01-01&to=2020-01-02&bucket=WEEKDAY_HOUR&mfcId="
                    + mfc))
        .isEqualTo(400);
    // 관리자는 센터 없이 모든 센터
    assertThat(ok(ADMIN, "/orders/metrics/series?" + days + "&bucket=DAY").path("items").size())
        .isGreaterThanOrEqualTo(2);
  }

  private static List<String> ids(JsonNode res) {
    List<String> out = new ArrayList<>();
    res.path("items").forEach(i -> out.add(i.path("orderId").asString()));
    return out;
  }
}
