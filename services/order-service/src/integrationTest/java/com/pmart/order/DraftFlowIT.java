package com.pmart.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.domain.OrderRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 초안: 정본 재확인(계정·배송지·판매 상태·상한·가격·중량) → 금액(배달팁·봉투·최소주문·첫 구매) → 차단 사유 → 10분 고정, 배송 방식 안내. 실제
 * PostgreSQL, customer·product·pricing·center-master·capacity·auth 는 WireMock 하나.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${pmart.order.customer-base-url}/api/v1/internal/service-tokens"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "deps",
        baseUrlProperties = {
          "pmart.order.customer-base-url",
          "pmart.order.product-base-url",
          "pmart.order.pricing-base-url",
          "pmart.order.center-master-base-url",
          "pmart.order.capacity-base-url",
          "pmart.order.orchestration-base-url"
        }))
class DraftFlowIT extends PostgresKafkaContainerSupport {

  static final String FIRST = "CU-FIRST";
  static final String REPEAT = "CU-REPEAT";
  static final String TOMORROW = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString();

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("deps")
  WireMockServer deps;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    deps.resetAll();
    stub(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens")),
        Map.of(
            "access_token",
            TestJwt.service("order-service"),
            "token_type",
            "Bearer",
            "expires_in",
            300));
    for (String acc : List.of(FIRST, REPEAT)) {
      stub(
          WireMock.get(urlPathEqualTo("/api/v1/internal/accounts/" + acc + "/status")),
          Map.of("accountId", acc, "status", "ACTIVE", "purchasable", true));
      stub(
          WireMock.get(
              urlPathEqualTo("/api/v1/internal/accounts/" + acc + "/first-purchase-checks")),
          Map.of(
              "accountId", acc, "firstPurchaseCandidate", acc.equals(FIRST), "matchedBy", "NONE"));
    }
    shoppingContext("ADR-IN", "SERVICEABLE", "MFC-1");
    shoppingContext("ADR-OUT", "OUT_OF_SERVICE_AREA", null);
    stub(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus")),
        Map.of(
            "items",
            List.of(
                sku("E-00000001", "ON_SALE", 320),
                sku("E-00000002", "TEMPORARILY_SOLD_OUT", 300),
                sku("E-00000003", "ON_SALE", 500),
                sku("E-00000009", "ON_SALE", 12_000)),
            "missing",
            List.of()));
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/purchase-limit-checks"))
            .atPriority(5)
            .willReturn(json(Map.of("passed", true, "violations", List.of()))));
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/purchase-limit-checks"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.items[?(@.quantity > 10)]"))
            .willReturn(
                json(
                    Map.of(
                        "passed",
                        false,
                        "violations",
                        List.of(
                            Map.of(
                                "baseProductId", "BP-1", "totalUnits", 11, "limitUnits", 10))))));
    quote(2, 1_900);
    quote(10, 1_900);
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/quotes"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.items[?(@.sku == 'E-00000003')]"))
            .willReturn(aResponse().withStatus(422)));
    // 고객 주문 운영 00:00~24:00 — 한집·알뜰 가능 여부는 심야(00~08)만 가른다
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/MFC-1/calendar"))
            .willReturn(
                json(
                    Map.of(
                        "open",
                        true,
                        "calendarVersion",
                        1,
                        "ranges",
                        List.of(Map.of("start", "00:00", "end", "24:00"))))));
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/slots"))
            .withQueryParam("mfcId", equalTo("MFC-1"))
            .willReturn(
                json(
                    Map.of(
                        "slots",
                        List.of(
                            slot("10:00", "11:00", "AVAILABLE"),
                            slot("11:00", "12:00", "FULL"))))));
  }

  void shoppingContext(String addressId, String state, String mfcId) {
    Map<String, Object> svc = new LinkedHashMap<>();
    svc.put("state", state);
    if (mfcId != null) {
      svc.put("mfcId", mfcId);
    }
    for (String acc : List.of(FIRST, REPEAT)) {
      deps.stubFor(
          WireMock.get(urlPathEqualTo("/api/v1/internal/accounts/" + acc + "/shopping-context"))
              .withQueryParam("shoppingAddressId", equalTo(addressId))
              .willReturn(
                  json(
                      Map.of(
                          "accountId",
                          acc,
                          "address",
                          Map.of("addressId", addressId, "serviceability", svc)))));
    }
  }

  void quote(int qty, long unit) {
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/quotes"))
            .atPriority(3)
            .withRequestBody(
                matchingJsonPath("$.items[?(@.sku == 'E-00000001' && @.quantity == " + qty + ")]"))
            .willReturn(
                json(
                    Map.of(
                        "quoteId",
                        "Q-" + qty,
                        "priceVersion",
                        4,
                        "itemAmount",
                        qty * unit,
                        "lines",
                        List.of(
                            Map.of(
                                "sku",
                                "E-00000001",
                                "quantity",
                                qty,
                                "unitPrice",
                                unit,
                                "lineAmount",
                                qty * unit)),
                        "calculatedAt",
                        Instant.now().toString()))));
  }

  static Map<String, Object> sku(String sku, String state, int weight) {
    return Map.of(
        "sku",
        sku,
        "productId",
        "PRD-" + sku,
        "baseProductId",
        "BP-" + sku,
        "unitType",
        "E",
        "unitsPerSku",
        1,
        "weightG",
        weight,
        "saleState",
        state,
        "version",
        1);
  }

  static Map<String, Object> slot(String start, String end, String availability) {
    return Map.of(
        "window",
        Map.of("date", TOMORROW, "start", start, "end", end),
        "availability",
        availability);
  }

  void stub(MappingBuilder b, Object body) {
    deps.stubFor(b.willReturn(json(body)));
  }

  com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(Object body) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  record Res(int status, JsonNode body) {}

  Res create(String token, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/order-drafts")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> draft(
      String intent, String sku, int qty, String address, String mode, Map<String, Object> window) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("purchaseIntentId", intent);
    m.put("items", List.of(Map.of("cartItemId", "CI-1", "sku", sku, "quantity", qty)));
    m.put("shoppingAddressId", address);
    m.put("deliveryMode", mode);
    if (window != null) {
      m.put("arrivalWindow", window);
    }
    m.put("outOfStockPreference", "CANCEL_OOS_ONLY");
    m.put("deliveryNote", "문 앞");
    return m;
  }

  static Map<String, Object> window(String start, String end) {
    return Map.of("date", TOMORROW, "start", start, "end", end);
  }

  @Test
  void draftQuoteBlockersAndOptions() {
    String first = TestJwt.customer(FIRST);
    String repeat = TestJwt.customer(REPEAT);

    // 첫 구매 회원, 예약 내일 10~11시, 두부 2개(3,800원) → 최소주문 미달 차단, 첫 구매 무료 예상
    String intent = "PI-" + UUID.randomUUID();
    Res d =
        create(
            first, draft(intent, "E-00000001", 2, "ADR-IN", "SCHEDULED", window("10:00", "11:00")));
    assertThat(d.status()).as(d.body().toString()).isEqualTo(201);
    JsonNode q = d.body().path("quote");
    assertThat(q.path("itemAmount").asInt()).isEqualTo(3_800);
    assertThat(q.path("deliveryTipBase").asInt()).isEqualTo(3_000);
    assertThat(q.path("deliveryTip").asInt()).isZero();
    assertThat(q.path("deliveryTipReason").asString()).isEqualTo("FIRST_PURCHASE");
    assertThat(q.path("firstPurchaseBenefit").asString()).isEqualTo("EXPECTED");
    assertThat(q.path("bagFee").asInt()).isEqualTo(100);
    assertThat(q.path("totalAmount").asInt()).isEqualTo(3_900);
    assertThat(q.path("minOrderSatisfied").asBoolean()).isFalse();
    assertThat(q.path("minOrderShortfall").asInt()).isEqualTo(11_200);
    assertThat(q.path("weightG").asInt()).isEqualTo(640);
    assertThat(q.path("plannedShipments").asInt()).isEqualTo(1);
    assertThat(q.path("priceVersion").asInt()).isEqualTo(4);
    assertThat(
            Duration.between(
                    Instant.now(),
                    OffsetDateTime.parse(q.path("quoteExpiresAt").asString()).toInstant())
                .toMinutes())
        .isBetween(8L, 10L);
    assertThat(d.body().path("blockers").path(0).path("code").asString())
        .isEqualTo("MIN_ORDER_AMOUNT");
    assertThat(d.body().path("blockers")).hasSize(1);
    assertThat(d.body().path("lines").path(0).path("unitPrice").asInt()).isEqualTo(1_900);
    assertThat(d.body().path("delivery").path("arrivalWindow").path("start").asString())
        .isEqualTo("10:00");
    assertThat(d.body().path("delivery").path("deliveryNote").asString()).isEqualTo("문 앞");
    assertThat(d.body().path("status").asString()).isEqualTo("DRAFT");
    assertThat(d.body().path("nextAction").asString()).isEqualTo("REVIEW");
    assertThat(d.body().path("mfcId").asString()).isEqualTo("MFC-1");
    String draftId = d.body().path("draftId").asString();
    // 같은 구매 의도 → 같은 초안
    assertThat(
            create(
                    first,
                    draft(intent, "E-00000001", 2, "ADR-IN", "SCHEDULED", window("10:00", "11:00")))
                .body()
                .path("draftId")
                .asString())
        .isEqualTo(draftId);
    assertThat(get(first, "/order-drafts/" + draftId).body().path("draftId").asString())
        .isEqualTo(draftId);
    assertThat(get(repeat, "/order-drafts/" + draftId).status()).as("남의 초안").isEqualTo(404);

    // 재구매 회원, 알뜰, 10개(19,000원) → 배달팁 3,000, 최소주문 충족. 한집·알뜰은 심야면 차단
    Res r =
        create(
            repeat, draft("PI-" + UUID.randomUUID(), "E-00000001", 10, "ADR-IN", "BUNDLED", null));
    assertThat(r.status()).isEqualTo(201);
    assertThat(r.body().path("quote").path("deliveryTip").asInt()).isEqualTo(3_000);
    assertThat(r.body().path("quote").path("deliveryTipReason").asString()).isEqualTo("STANDARD");
    assertThat(r.body().path("quote").path("firstPurchaseBenefit").asString())
        .isEqualTo("NOT_ELIGIBLE");
    assertThat(r.body().path("quote").path("totalAmount").asInt()).isEqualTo(22_100);
    if (OrderRules.nightTime(Instant.now())) {
      assertThat(r.body().path("blockers").path(0).path("detail").asString())
          .isEqualTo("NIGHT_TIME");
    } else {
      assertThat(r.body().path("blockers")).isEmpty();
    }

    // 예약: 시간 미선택·마감 슬롯·제공 안 하는 시간대(07시)
    assertThat(
            codes(
                create(
                    repeat,
                    draft(
                        "PI-" + UUID.randomUUID(), "E-00000001", 10, "ADR-IN", "SCHEDULED", null))))
        .contains("SLOT_REQUIRED");
    Res full =
        create(
            repeat,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("11:00", "12:00")));
    assertThat(full.body().path("blockers").path(0).path("detail").asString()).isEqualTo("FULL");
    Res early =
        create(
            repeat,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("07:00", "08:00")));
    assertThat(early.body().path("blockers").path(0).path("detail").asString())
        .isEqualTo("NOT_OFFERED");

    // 거절
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000002", 1, "ADR-IN", "SINGLE", null))))
        .isEqualTo("SKU_NOT_SALEABLE");
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000001", 11, "ADR-IN", "SINGLE", null))))
        .isEqualTo("QUANTITY_LIMIT_EXCEEDED");
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000001", 2, "ADR-OUT", "SINGLE", null))))
        .isEqualTo("OUT_OF_SERVICE_AREA");
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000003", 1, "ADR-IN", "SINGLE", null))))
        .isEqualTo("PRICE_NOT_SET");
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000009", 1, "ADR-IN", "SINGLE", null))))
        .isEqualTo("WEIGHT_UNSPLITTABLE");
    // 비회원·운영자는 주문 초안 불가
    String guest = TestJwt.token("GST-1", "GUEST", List.of(), List.of(), "it");
    assertThat(create(guest, draft("PI-x", "E-00000001", 2, "ADR-IN", "SINGLE", null)).status())
        .isEqualTo(403);

    // 배송 방식 안내(비회원): 예상시간 미설정, 기본 배달팁, 30,000 이상이면 무료, 가장 빠른 예약 슬롯
    Res o = get(guest, "/delivery-options?mfcId=MFC-1&itemAmount=31000");
    assertThat(o.status()).isEqualTo(200);
    JsonNode single = o.body().path("options").path(0);
    assertThat(single.path("mode").asString()).isEqualTo("SINGLE");
    assertThat(single.path("eta").path("state").asString()).isEqualTo("NOT_SET");
    assertThat(single.path("deliveryTip").path("base").asInt()).isEqualTo(4_000);
    assertThat(single.path("deliveryTip").path("applied").asInt()).isZero();
    assertThat(single.path("deliveryTip").path("reason").asString()).isEqualTo("OVER_30000");
    assertThat(single.path("availability").asString())
        .isEqualTo(OrderRules.nightTime(Instant.now()) ? "UNAVAILABLE" : "AVAILABLE");
    JsonNode scheduled = o.body().path("options").path(2);
    assertThat(scheduled.path("availability").asString()).isEqualTo("AVAILABLE");
    assertThat(scheduled.path("earliestSlot").path("start").asString()).isEqualTo("10:00");
    // 첫 구매 회원 + 금액 → 무료 예상, 금액 없으면 적용액 없음
    assertThat(
            get(first, "/delivery-options?mfcId=MFC-1&itemAmount=5000")
                .body()
                .path("options")
                .path(1)
                .path("deliveryTip")
                .path("reason")
                .asString())
        .isEqualTo("FIRST_PURCHASE");
    assertThat(
            get(repeat, "/delivery-options?mfcId=MFC-1")
                .body()
                .path("options")
                .path(1)
                .path("deliveryTip")
                .path("applied")
                .isNull())
        .isTrue();
  }

  Res checkout(String token, String draftId, String key, int quoteVersion) {
    var e =
        http.post()
            .uri("/order-drafts/{id}/checkouts", draftId)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("acceptedQuoteVersion", quoteVersion, "paymentMethod", "TOSS_PAYMENTS"))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void saga(String sagaId, Map<String, Object> body) {
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/sagas/" + sagaId)).willReturn(json(body)));
  }

  static Map<String, Object> step(String step, String state) {
    return Map.of("step", step, "state", state);
  }

  /** 결제 진입: 견적·차단 사유 확인 → 사가 시작(202) → 사가 사본으로 진행·결제창 정보·실패 반영. 사가는 WireMock. */
  @Test
  void checkoutStartsSagaAndReflectsProgress() {
    String repeat = TestJwt.customer(REPEAT);
    // 재구매 회원, 예약 내일 10~11시, 10개(19,000원) → 차단 사유 없음
    Res d =
        create(
            repeat,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("10:00", "11:00")));
    assertThat(d.body().path("blockers")).as(d.body().toString()).isEmpty();
    String draftId = d.body().path("draftId").asString();
    int quoteVersion = d.body().path("quote").path("quoteVersion").asInt();
    String sagaId = "SAGA-" + UUID.randomUUID();
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/checkout-sagas"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of("status", "ACCEPTED", "requestId", "k", "resultId", sagaId)))));
    saga(
        sagaId,
        Map.of(
            "sagaId",
            sagaId,
            "state",
            "WAITING_RESULT",
            "currentStep",
            "AWAIT_PAYMENT_RESULT",
            "steps",
            List.of(
                step("RESERVE_INVENTORY", "SUCCEEDED"),
                step("HOLD_CAPACITY", "SUCCEEDED"),
                step("HOLD_SLOT", "SUCCEEDED"),
                step("START_PAYMENT", "SUCCEEDED")),
            "timers",
            Map.of("reservationExpiresAt", "2026-10-31T01:10:00Z"),
            "payment",
            Map.of(
                "paymentAttemptId",
                "PAY-1",
                "amount",
                22_100,
                "pgOrderId",
                "PAY-1",
                "clientKey",
                "test_ck_docs",
                "expiresAt",
                "2026-10-31T01:09:00Z")));

    // 견적 버전이 다르면 다시 견적
    assertThat(code(checkout(repeat, draftId, UUID.randomUUID().toString(), quoteVersion + 1)))
        .isEqualTo("RE_QUOTE_REQUIRED");

    String key = UUID.randomUUID().toString();
    Res c = checkout(repeat, draftId, key, quoteVersion);
    assertThat(c.status()).as(c.body().toString()).isEqualTo(202);
    String orderId = c.body().path("resultId").asString();
    assertThat(orderId).startsWith("ORD-");
    // 같은 키 재전송, 진행 중 다른 키 → 같은 주문(새 사가 없음)
    assertThat(checkout(repeat, draftId, key, quoteVersion).body().path("resultId").asString())
        .isEqualTo(orderId);
    assertThat(
            checkout(repeat, draftId, UUID.randomUUID().toString(), quoteVersion)
                .body()
                .path("resultId")
                .asString())
        .isEqualTo(orderId);
    deps.verify(
        1,
        WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/checkout-sagas"))
            .withRequestBody(matchingJsonPath("$.orderId", equalTo(orderId)))
            .withRequestBody(matchingJsonPath("$.customerRef", equalTo(REPEAT)))
            .withRequestBody(matchingJsonPath("$.amount", equalTo("22100")))
            .withRequestBody(matchingJsonPath("$.arrivalWindow.start", equalTo("10:00")))
            .withRequestBody(matchingJsonPath("$.lines[0].quantity", equalTo("10"))));

    JsonNode g = get(repeat, "/order-drafts/" + draftId).body();
    assertThat(g.path("status").asString()).isEqualTo("PAYMENT_PENDING");
    assertThat(g.path("nextAction").asString()).isEqualTo("PAY");
    assertThat(g.path("orderId").asString()).isEqualTo(orderId);
    assertThat(g.path("payment").path("clientKey").asString()).isEqualTo("test_ck_docs");
    assertThat(g.path("payment").path("pgOrderId").asString()).isEqualTo("PAY-1");
    assertThat(g.path("resources").path("inventoryReservation").asString()).isEqualTo("HELD");
    assertThat(g.path("resources").path("slotHold").asString()).isEqualTo("HELD");
    assertThat(g.path("resources").path("payment").asString()).isEqualTo("STARTED");

    // 사가가 재고 부족으로 보상 종료 → 초안은 다시 검토, 실패 사유 표시
    saga(
        sagaId,
        Map.of(
            "sagaId",
            sagaId,
            "state",
            "COMPENSATED",
            "currentStep",
            "RESERVE_INVENTORY",
            "steps",
            List.of(step("RESERVE_INVENTORY", "FAILED")),
            "lastError",
            Map.of("step", "RESERVE_INVENTORY", "code", "INSUFFICIENT_STOCK", "message", "재고 부족")));
    JsonNode failed = get(repeat, "/order-drafts/" + draftId).body();
    assertThat(failed.path("status").asString()).isEqualTo("DRAFT");
    assertThat(failed.path("nextAction").asString()).isEqualTo("REVIEW");
    assertThat(failed.path("checkoutFailure").path("code").asString())
        .isEqualTo("INSUFFICIENT_STOCK");
    assertThat(failed.path("resources").path("inventoryReservation").asString())
        .isEqualTo("FAILED");

    // 차단 사유가 남은 초안(최소주문 미달)은 결제 진입 불가
    Res blocked =
        create(
            repeat, draft("PI-" + UUID.randomUUID(), "E-00000001", 2, "ADR-IN", "BUNDLED", null));
    assertThat(
            code(
                checkout(
                    repeat,
                    blocked.body().path("draftId").asString(),
                    UUID.randomUUID().toString(),
                    blocked.body().path("quote").path("quoteVersion").asInt())))
        .isEqualTo("CHECKOUT_BLOCKED");
  }

  static String code(Res r) {
    return r.body().path("code").asString();
  }

  static List<String> codes(Res r) {
    return r.body().path("blockers").findValuesAsString("code");
  }
}
