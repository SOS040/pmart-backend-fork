package com.pmart.order;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 직원용 주문 검색·상세(2026-11-15, O-6, 계약 0.3.0): 역할 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 만, 기간 상한, 연락처 완전
 * 일치·목록 가림, 상세는 배송지 전체.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=lookup-test"
    })
@Import(TestJwtConfiguration.class)
class StaffOrderLookupIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired OrderStore store;
  @Autowired JdbcTemplate jdbc;

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
            confirmedAt);
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
            null));
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
            "1L"));
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
    assertThat(d.path("deliveryNote").asString()).isEqualTo("문 앞에 두세요");
    assertThat(d.path("amounts").path("totalAmount").asInt()).isEqualTo(24_100);
    assertThat(d.path("amounts").path("approvedAmount").asInt()).isEqualTo(24_100);
    assertThat(d.path("amounts").path("deliveryTip").asInt()).isEqualTo(4_000);
    assertThat(d.path("payment").path("paymentAttemptId").asString()).isEqualTo("PA-" + orderId);
    assertThat(d.path("lines")).hasSize(2);
    assertThat(d.path("lines").path(0).path("productName").asString()).isEqualTo("사과");
    assertThat(d.path("lines").path(0).path("originalQty").asInt()).isEqualTo(2);
    JsonNode s = d.path("shippingAddress");
    assertThat(s.path("receiverName").asString()).isEqualTo("홍길동");
    assertThat(s.path("receiverPhone").asString()).isEqualTo("010-2222-3333");
    assertThat(s.path("roadAddress").asString()).isEqualTo("서울시 강남구 테헤란로 1");
    assertThat(s.path("detailAddress").asString()).isEqualTo("101동 1001호");
    assertThat(s.path("entranceNote").asString()).isEqualTo("#1234");
    assertThat(d.path("fulfillmentIssues").isArray()).isTrue();

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
  }

  private static List<String> ids(JsonNode res) {
    List<String> out = new ArrayList<>();
    res.path("items").forEach(i -> out.add(i.path("orderId").asString()));
    return out;
  }
}
