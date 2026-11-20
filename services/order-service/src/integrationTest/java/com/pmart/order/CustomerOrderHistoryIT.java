package com.pmart.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.application.CenterNames;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.LineRow;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import com.pmart.order.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 주문 내역(2026-11-18, 계약 0.12.0 — 고객 앱, 사용자 "A해줘"): 목록 대표 상품·배송 방식, 기간(Asia/Seoul 날짜, 둘 다 포함, 기본
 * 최근 3개월)·상품명 검색, 숨기기(끝난 주문만, 목록에서만 빠지고 상세는 그대로).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=history-test"
    })
@Import({TestJwtConfiguration.class, CustomerOrderHistoryIT.Centers.class})
@org.wiremock.spring.EnableWireMock(
    @org.wiremock.spring.ConfigureWireMock(
        name = "content",
        baseUrlProperties = {"pmart.order.content-base-url"}))
class CustomerOrderHistoryIT extends PostgresKafkaContainerSupport {

  @org.wiremock.spring.InjectWireMock("content")
  com.github.tomakehurst.wiremock.WireMockServer content;

  /** 센터 이름(BR-68 mfcName) — center-master 대신 고정 이름. 상세와 같은 CenterNames 를 쓰는지 본다. */
  @TestConfiguration
  static class Centers {
    @Bean
    @Primary
    CenterNames fixedCenterNames(Upstreams upstreams, Clock clock) {
      return new CenterNames(upstreams, clock) {
        @Override
        public @Nullable String name(@Nullable String mfcId) {
          return "MFC-HS-01".equals(mfcId) ? "강남 MFC" : null;
        }
      };
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired OrderStore store;
  @Autowired JdbcTemplate jdbc;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  /** path 의 쿼리 값은 날 글자로 준다(공백·% 는 RestClient 가 인코딩). */
  ResponseEntity<String> get(String token, String path) {
    return http.get()
        .uri(path)
        .headers(h -> h.setBearerAuth(token))
        .retrieve()
        .toEntity(String.class);
  }

  JsonNode ok(String token, String path) {
    var e = get(token, path);
    assertThat(e.getStatusCode().value()).as(e.getBody()).isEqualTo(200);
    return mapper.readTree(e.getBody());
  }

  List<String> ids(String token, String path) {
    return ok(token, path).path("items").findValuesAsString("orderId");
  }

  ResponseEntity<String> hide(String token, String orderId, String key) {
    return http.post()
        .uri("/orders/{id}/hides", orderId)
        .headers(h -> h.setBearerAuth(token))
        .header("Idempotency-Key", key)
        .header("X-Source-System", "customer-app")
        .retrieve()
        .toEntity(String.class);
  }

  String code(ResponseEntity<String> e) {
    return mapper.readTree(e.getBody()).path("code").asString();
  }

  /** 확정 주문 하나를 원장에 바로 넣는다(사가 없이 — 조회·숨김만 시험). names 마다 행 하나(수량 2, 1, 1, …). */
  String order(
      String account,
      String status,
      Instant confirmedAt,
      String deliveryMode,
      @Nullable String... names) {
    String orderId = "ORD-HS-" + UUID.randomUUID();
    var o =
        new OrderRow(
            orderId,
            account,
            "DRF-" + orderId,
            "MFC-HS-01",
            status,
            3,
            deliveryMode,
            null,
            null,
            null,
            "CANCEL_OOS_ONLY",
            null,
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
    for (int i = 0; i < names.length; i++) {
      int qty = i == 0 ? 2 : 1;
      lines.add(
          new LineRow(
              orderId + "-L" + (i + 1),
              orderId,
              i + 1,
              "CI-" + (i + 1),
              "E-0000000" + (i + 1),
              "P-" + (i + 1),
              1,
              qty,
              5_000,
              5_000L * qty,
              0,
              500,
              qty,
              qty,
              0,
              0,
              0,
              0,
              names[i],
              i == 0 ? "300g" : null,
              0));
    }
    store.insert(o, lines);
    return orderId;
  }

  static String account() {
    return "ACC-HS-" + UUID.randomUUID();
  }

  @Test
  void summaryCarriesRepresentativeItemAndDeliveryMode() {
    String acc = account();
    String token = TestJwt.customer(acc);
    String id =
        order(
            acc,
            "CONFIRMED",
            Instant.now().minus(Duration.ofHours(1)),
            "SCHEDULED",
            "국산콩 두부",
            "우유",
            "계란");
    // 사본 이름이 없는 옛 주문(2026-11-02 이전) — 대표 상품은 sku 만
    String legacy =
        order(acc, "COMPLETED", Instant.now().minus(Duration.ofHours(2)), "SINGLE", (String) null);

    JsonNode items = ok(token, "/orders").path("items");
    assertThat(items.findValuesAsString("orderId")).containsExactly(id, legacy);
    JsonNode first = items.path(0);
    assertThat(first.path("deliveryMode").asString()).isEqualTo("SCHEDULED");
    assertThat(first.path("itemCount").asInt()).isEqualTo(4);
    JsonNode rep = first.path("representativeItem");
    assertThat(rep.path("productId").asString()).isEqualTo("P-1");
    assertThat(rep.path("sku").asString()).isEqualTo("E-00000001");
    assertThat(rep.path("productName").asString()).isEqualTo("국산콩 두부");
    assertThat(rep.path("optionLabel").asString()).isEqualTo("300g");
    assertThat(rep.path("quantity").asInt()).isEqualTo(2);
    JsonNode old = items.path(1);
    assertThat(old.path("deliveryMode").asString()).isEqualTo("SINGLE");
    assertThat(old.path("representativeItem").path("sku").asString()).isEqualTo("E-00000001");
    assertThat(old.path("representativeItem").path("productName").isNull()).isTrue();
  }

  /** BR-68(0.17.0): 앞 주문행 최대 5개(line 순, 사본 그대로)·센터 이름. 쪽 안 주문마다 따로 — 행이 섞이지 않는다. */
  @Test
  void summaryCarriesFirstFiveLinesAndCenterName() {
    String acc = account();
    String token = TestJwt.customer(acc);
    String seven =
        order(
            acc,
            "CONFIRMED",
            Instant.now().minus(Duration.ofHours(1)),
            "SINGLE",
            "두부",
            "우유",
            "계란",
            "콩나물",
            "대파",
            "양파",
            "감자");
    String two =
        order(acc, "COMPLETED", Instant.now().minus(Duration.ofHours(2)), "SINGLE", "사과", null);

    // BR-71(0.19.0): 지금 대표 사진 — P-1 만 게시(카드 있음), 나머지는 null
    content.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/product-contents"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"productId\":\"P-1\",\"mainImage\":{\"url\":\"/api/v1/content-images/IMG-P1\"}}]}")));
    JsonNode items = ok(token, "/orders").path("items");
    assertThat(items.findValuesAsString("orderId")).containsExactly(seven, two);
    assertThat(items.path(0).path("lines").path(0).path("imageUrl").asString())
        .isEqualTo("/api/v1/content-images/IMG-P1");
    assertThat(items.path(0).path("lines").path(1).path("imageUrl").isNull()).isTrue();
    JsonNode detailLines = ok(token, "/orders/" + seven).path("lines");
    assertThat(detailLines.path(0).path("imageUrl").asString())
        .isEqualTo("/api/v1/content-images/IMG-P1");
    assertThat(detailLines.path(2).path("imageUrl").isNull()).isTrue();
    JsonNode first = items.path(0);
    assertThat(first.path("mfcName").asString()).isEqualTo("강남 MFC");
    assertThat(first.path("itemCount").asInt()).isEqualTo(8); // 전체 수량(2 + 1×6)은 그대로
    JsonNode lines = first.path("lines");
    assertThat(lines.size()).isEqualTo(5);
    assertThat(lines.findValuesAsString("orderLineId"))
        .containsExactly(seven + "-L1", seven + "-L2", seven + "-L3", seven + "-L4", seven + "-L5");
    assertThat(lines.findValuesAsString("productName"))
        .containsExactly("두부", "우유", "계란", "콩나물", "대파");
    JsonNode l1 = lines.path(0);
    assertThat(l1.path("productId").asString()).isEqualTo("P-1");
    assertThat(l1.path("sku").asString()).isEqualTo("E-00000001");
    assertThat(l1.path("optionLabel").asString()).isEqualTo("300g");
    assertThat(l1.path("quantity").asInt()).isEqualTo(2);
    assertThat(l1.path("lineAmount").asInt()).isEqualTo(10_000);
    assertThat(lines.path(1).path("optionLabel").isNull()).isTrue();
    assertThat(lines.path(1).path("lineAmount").asInt()).isEqualTo(5_000);

    JsonNode second = items.path(1);
    assertThat(second.path("lines").findValuesAsString("orderLineId"))
        .containsExactly(two + "-L1", two + "-L2");
    assertThat(second.path("lines").path(1).path("productName").isNull()).isTrue();
  }

  /** BR-68: to 만으로도 거른다(그날 포함) — to < from 은 400(0.12.0 그대로). */
  @Test
  void toOnlyFiltersInclusiveAndToBeforeFromIs400() {
    String acc = account();
    String token = TestJwt.customer(acc);
    // 2026-03-10 23:30 KST = 14:30Z, 2026-03-11 00:10 KST = 2026-03-10T15:10Z
    String tenth = order(acc, "COMPLETED", Instant.parse("2026-03-10T14:30:00Z"), "SINGLE", "a");
    String eleventh = order(acc, "COMPLETED", Instant.parse("2026-03-10T15:10:00Z"), "SINGLE", "b");
    assertThat(ids(token, "/orders?to=2026-03-10")).containsExactly(tenth);
    assertThat(ids(token, "/orders?to=2026-03-11")).containsExactly(eleventh, tenth);
    assertThat(get(token, "/orders?from=2026-03-11&to=2026-03-10").getStatusCode().value())
        .isEqualTo(400);
  }

  @Test
  void defaultPeriodIsLastThreeMonthsAndExplicitDatesAreSeoulInclusive() {
    String acc = account();
    String token = TestJwt.customer(acc);
    String recent =
        order(acc, "COMPLETED", Instant.now().minus(Duration.ofDays(10)), "SINGLE", "a");
    String older =
        order(acc, "COMPLETED", Instant.now().minus(Duration.ofDays(130)), "SINGLE", "b");
    // 2026-01-31 23:59 Asia/Seoul 과 2026-02-01 00:00 Asia/Seoul
    String lastOfJan =
        order(acc, "COMPLETED", Instant.parse("2026-01-31T14:59:00Z"), "SINGLE", "c");
    String firstOfFeb =
        order(acc, "COMPLETED", Instant.parse("2026-01-31T15:00:00Z"), "SINGLE", "d");

    // 기본 = 최근 3개월
    assertThat(ids(token, "/orders")).containsExactly(recent);
    // 날짜는 Asia/Seoul, 양 끝 포함
    assertThat(ids(token, "/orders?from=2026-01-31&to=2026-01-31")).containsExactly(lastOfJan);
    assertThat(ids(token, "/orders?from=2026-02-01&to=2026-02-01")).containsExactly(firstOfFeb);
    assertThat(ids(token, "/orders?from=2026-01-31&to=2026-02-01"))
        .containsExactly(firstOfFeb, lastOfJan);
    // 한쪽만: to 만 → 처음부터 그날까지, from 만 → 그날부터 지금까지
    assertThat(ids(token, "/orders?to=2026-01-31")).containsExactly(lastOfJan);
    assertThat(ids(token, "/orders?from=2026-02-01")).contains(recent, older, firstOfFeb);
    assertThat(ids(token, "/orders?from=2026-02-01")).doesNotContain(lastOfJan);
    // from > to 는 400
    var bad = get(token, "/orders?from=2026-02-02&to=2026-02-01");
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void nameSearchMatchesAnyLineCaseInsensitiveWithPaging() {
    String acc = account();
    String token = TestJwt.customer(acc);
    String tofu =
        order(acc, "COMPLETED", Instant.now().minus(Duration.ofHours(3)), "SINGLE", "국산콩 두부");
    String apple =
        order(
            acc,
            "COMPLETED",
            Instant.now().minus(Duration.ofHours(2)),
            "SINGLE",
            "우유",
            "Fresh APPLE 1kg");
    String apple2 =
        order(acc, "CONFIRMED", Instant.now().minus(Duration.ofHours(1)), "SINGLE", "apple juice");

    assertThat(ids(token, "/orders?q=두부")).containsExactly(tofu);
    assertThat(ids(token, "/orders?q= Apple ")).containsExactly(apple2, apple);
    // 와일드카드는 글자 그대로
    assertThat(ids(token, "/orders?q=%")).isEmpty();
    assertThat(ids(token, "/orders?q=  ")).containsExactly(apple2, apple, tofu);
    // 상태 필터와 함께
    assertThat(ids(token, "/orders?q=apple&status=COMPLETED")).containsExactly(apple);
    // 쪽 나눔 — 다음 쪽도 같은 조건으로
    JsonNode page1 = ok(token, "/orders?q=apple&size=1");
    assertThat(page1.path("items").findValuesAsString("orderId")).containsExactly(apple2);
    String cursor = page1.path("page").path("nextCursor").asString();
    assertThat(cursor).isNotBlank();
    JsonNode page2 = ok(token, "/orders?q=apple&size=1&cursor=" + cursor);
    assertThat(page2.path("items").findValuesAsString("orderId")).containsExactly(apple);
    assertThat(
            page2.path("page").path("nextCursor").isNull()
                || page2.path("page").path("nextCursor").isMissingNode())
        .isTrue();
    // 101자 검색어는 400
    assertThat(get(token, "/orders?q=" + "a".repeat(101)).getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void hideOnlyFinishedOwnOrdersIdempotentlyAndKeepDetail() {
    String acc = account();
    String token = TestJwt.customer(acc);
    String other = TestJwt.customer(account());
    String done = order(acc, "COMPLETED", Instant.now().minus(Duration.ofHours(3)), "SINGLE", "a");
    String cancelled =
        order(acc, "CANCELLED", Instant.now().minus(Duration.ofHours(2)), "SINGLE", "b");
    String live = order(acc, "PREPARING", Instant.now().minus(Duration.ofHours(1)), "SINGLE", "c");

    // 진행 중은 409, 남의 주문·없는 주문은 404, 고객 아닌 토큰은 403
    var notFinished = hide(token, live, UUID.randomUUID().toString());
    assertThat(notFinished.getStatusCode().value()).isEqualTo(409);
    assertThat(code(notFinished)).isEqualTo("ORDER_NOT_FINISHED");
    assertThat(hide(other, done, UUID.randomUUID().toString()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(hide(token, "ORD-NOPE", UUID.randomUUID().toString()).getStatusCode().value())
        .isEqualTo(404);
    String operator = TestJwt.operator("op-1", List.of("OPERATOR_ADMIN"), List.of());
    assertThat(hide(operator, done, UUID.randomUUID().toString()).getStatusCode().value())
        .isEqualTo(403);

    String key = UUID.randomUUID().toString();
    var hidden = hide(token, done, key);
    assertThat(hidden.getStatusCode().value()).as(hidden.getBody()).isEqualTo(200);
    JsonNode body = mapper.readTree(hidden.getBody());
    assertThat(body.path("status").asString()).isEqualTo("APPLIED");
    assertThat(body.path("resultId").asString()).isEqualTo(done);
    assertThat(body.path("version").asInt()).isEqualTo(3);
    Instant firstHiddenAt =
        jdbc.queryForObject(
                "SELECT hidden_at FROM customer_order WHERE order_id = ?",
                java.sql.Timestamp.class,
                done)
            .toInstant();
    // 같은 키 재생, 다른 키로 다시 숨겨도 그대로(처음 시각 유지)
    assertThat(hide(token, done, key).getStatusCode().value()).isEqualTo(200);
    var again = hide(token, done, UUID.randomUUID().toString());
    assertThat(again.getStatusCode().value()).isEqualTo(200);
    assertThat(mapper.readTree(again.getBody()).path("status").asString()).isEqualTo("APPLIED");
    assertThat(
            jdbc.queryForObject(
                    "SELECT hidden_at FROM customer_order WHERE order_id = ?",
                    java.sql.Timestamp.class,
                    done)
                .toInstant())
        .isEqualTo(firstHiddenAt);
    assertThat(hide(token, cancelled, UUID.randomUUID().toString()).getStatusCode().value())
        .isEqualTo(200);

    // 목록에서 빠지고(기간·검색과 상관없이), 상세는 그대로 — 상태·버전 불변
    assertThat(ids(token, "/orders")).containsExactly(live);
    assertThat(ids(token, "/orders?q=a")).isEmpty();
    JsonNode detail = ok(token, "/orders/" + done);
    assertThat(detail.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.path("version").asInt()).isEqualTo(3);
  }
}
