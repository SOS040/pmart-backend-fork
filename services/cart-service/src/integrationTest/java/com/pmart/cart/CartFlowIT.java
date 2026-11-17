package com.pmart.cart;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 장바구니: 비회원 토큰으로 열기·담기(절대 수량·멱등·버전 충돌·판매 상태·구매 상한) → 표시 사본 → 회원 장바구니 → 병합(X-Guest-Token, 항목별 재검증·부분
 * 성공·재전송) → 주문용 내부 조회. 실제 PostgreSQL, product·content·pricing·mfc-inventory·auth(서비스 토큰)는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${pmart.cart.product-base-url}/api/v1/internal/service-tokens"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "deps",
        baseUrlProperties = {
          "pmart.cart.product-base-url",
          "pmart.cart.content-base-url",
          "pmart.cart.pricing-base-url",
          "pmart.cart.inventory-base-url"
        }))
class CartFlowIT extends PostgresKafkaContainerSupport {

  static final String SERVICE_TOKEN = TestJwt.service("cart-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

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
    json(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens")),
        Map.of("access_token", SERVICE_TOKEN, "token_type", "Bearer", "expires_in", 300));
    sku("E-00000001", "PRD-1", "BP-1", "ON_SALE");
    sku("E-00000002", "PRD-2", "BP-2", "TEMPORARILY_SOLD_OUT");
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus/E-00000404"))
            .willReturn(aResponse().withStatus(404)));
    json(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus")),
        Map.of(
            "items",
            List.of(detail("E-00000001", "PRD-1", "BP-1", "ON_SALE")),
            "missing",
            List.of()));
    // 구매 상한: E-1 10개 초과면 위반, 아니면 통과
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/purchase-limit-checks"))
            .atPriority(5)
            .willReturn(okJson(Map.of("passed", true, "violations", List.of()))));
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/purchase-limit-checks"))
            .atPriority(1)
            .withRequestBody(
                matchingJsonPath("$.items[?(@.sku == 'E-00000001' && @.quantity > 10)]"))
            .willReturn(
                okJson(
                    Map.of(
                        "passed",
                        false,
                        "violations",
                        List.of(
                            Map.of(
                                "baseProductId", "BP-1", "totalUnits", 11, "limitUnits", 10))))));
    json(
        WireMock.get(urlPathEqualTo("/api/v1/product-contents")),
        Map.of(
            "items",
            List.of(
                Map.of(
                    "productId", "PRD-1",
                    "name", "[시험] 국산콩 두부 300g",
                    "mainImage", Map.of("url", "/api/v1/content-images/IMG-1")))));
    json(
        WireMock.get(urlPathEqualTo("/api/v1/prices")),
        Map.of(
            "items", List.of(Map.of("sku", "E-00000001", "basePrice", 1900, "priceVersion", 1))));
  }

  Map<String, Object> detail(String sku, String productId, String base, String saleState) {
    return Map.of(
        "sku", sku,
        "productId", productId,
        "baseProductId", base,
        "unitType", "E",
        "unitsPerSku", 1,
        "saleState", saleState,
        "version", 1);
  }

  void sku(String sku, String productId, String base, String saleState) {
    json(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus/" + sku)),
        detail(sku, productId, base, saleState));
  }

  /**
   * mfc-inventory 가용(MFC-SEED-01). 장바구니는 출하 가능 수량(shippableAvailable, 2026-11-18 IV-1)을 쓴다 — 시험은
   * available 을 일부러 더 크게(소비기한 부적격 LOT 이 있는 것처럼) 줘서 available 로 판정하면 틀리게 한다.
   */
  void stock(String sku, int shippable, boolean handlingEnabled) {
    stockItem(sku, shippable + 7, shippable, handlingEnabled);
  }

  void stockItem(String sku, int available, @Nullable Integer shippable, boolean handlingEnabled) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("sku", sku);
    item.put("available", available);
    item.put("shippableAvailable", shippable);
    item.put("handlingEnabled", handlingEnabled);
    item.put("version", 1);
    json(
        WireMock.get(urlPathEqualTo("/api/v1/internal/availability"))
            .withQueryParam("mfcId", equalTo("MFC-SEED-01")),
        Map.of("mfcId", "MFC-SEED-01", "items", List.of(item)));
  }

  void json(com.github.tomakehurst.wiremock.client.MappingBuilder b, Object body) {
    deps.stubFor(b.willReturn(okJson(body)));
  }

  com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(Object body) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  record Res(int status, JsonNode body) {}

  Res call(
      HttpMethod m, String path, String token, Object body, String key, Map<String, String> h) {
    var spec =
        http.method(m)
            .uri(path)
            .headers(
                (HttpHeaders x) -> {
                  if (token != null) {
                    x.setBearerAuth(token);
                  }
                  x.set("X-Source-System", "customer-app");
                  if (m != HttpMethod.GET) {
                    x.set("Idempotency-Key", key == null ? UUID.randomUUID().toString() : key);
                  }
                  h.forEach(x::set);
                });
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    var e = spec.retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(),
        mapper.readTree(e.getBody() == null || e.getBody().isBlank() ? "{}" : e.getBody()));
  }

  Res post(String path, String token, Object body) {
    return call(HttpMethod.POST, path, token, body, null, Map.of());
  }

  Res get(String path, String token) {
    return call(HttpMethod.GET, path, token, null, null, Map.of());
  }

  Res add(String token, String cartId, String sku, int qty, int expected, String key) {
    return call(
        HttpMethod.POST,
        "/carts/" + cartId + "/items",
        token,
        Map.of("sku", sku, "quantity", qty, "expectedVersion", expected),
        key,
        Map.of());
  }

  @Test
  void guestCartThenMemberMerge() {
    String guest = TestJwt.token("GST-" + UUID.randomUUID(), "GUEST", List.of(), List.of(), "it");
    String member = TestJwt.customer("CU-" + UUID.randomUUID());

    // 운영자는 장바구니 불가
    assertThat(post("/carts", TestJwt.operator("ops-1", List.of("MD"), List.of()), null).status())
        .isEqualTo(403);

    // 비회원 장바구니
    Res opened = post("/carts", guest, Map.of("mfcId", "MFC-SEED-01"));
    assertThat(opened.status()).isEqualTo(201);
    String guestCart = opened.body().path("cartId").asString();
    assertThat(opened.body().path("ownerType").asString()).isEqualTo("GUEST");
    assertThat(opened.body().path("cartVersion").asInt()).isZero();
    assertThat(post("/carts", guest, null).body().path("cartId").asString()).isEqualTo(guestCart);

    // 담기: 절대 수량·멱등·버전
    String key = UUID.randomUUID().toString();
    Res added = add(guest, guestCart, "E-00000001", 2, 0, key);
    assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
    assertThat(added.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(added.body().path("cartVersion").asInt()).isEqualTo(1);
    String guestItem = added.body().path("cartItemId").asString();
    // BR-43: 변경 응답에 그 시점의 장바구니(GET 과 같은 모양) — 앱이 다시 조회하지 않아도 된다
    JsonNode addedCart = added.body().path("cart");
    assertThat(addedCart.path("cartVersion").asInt()).isEqualTo(1);
    assertThat(addedCart.path("items").path(0).path("cartItemId").asString()).isEqualTo(guestItem);
    assertThat(addedCart.path("items").path(0).path("quantity").asInt()).isEqualTo(2);
    Res replay = add(guest, guestCart, "E-00000001", 2, 0, key);
    assertThat(replay.body().path("cartVersion").asInt()).as("같은 키 재전송은 같은 결과").isEqualTo(1);
    assertThat(add(guest, guestCart, "E-00000001", 3, 0, key).body().path("code").asString())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    Res stale = add(guest, guestCart, "E-00000001", 3, 0, null);
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.body().path("code").asString()).isEqualTo("CART_VERSION_CONFLICT");
    // 서비스 토큰으로 product 내부 API 를 불렀다
    deps.verify(
        getRequestedFor(urlPathMatching("/api/v1/internal/skus/E-00000001"))
            .withoutHeader("Authorization") // 서비스 간 인증 없음(ADR-0026)
            .withHeader("X-Source-System", equalTo("cart-service")));

    // 판매 상태·상한
    assertThat(add(guest, guestCart, "E-00000002", 1, 1, null).body().path("code").asString())
        .isEqualTo("SKU_NOT_SALEABLE");
    assertThat(add(guest, guestCart, "E-00000404", 1, 1, null).body().path("code").asString())
        .isEqualTo("SKU_NOT_SALEABLE");
    Res over = add(guest, guestCart, "E-00000001", 11, 1, null);
    assertThat(over.status()).isEqualTo(422);
    assertThat(over.body().path("code").asString()).isEqualTo("QUANTITY_LIMIT_EXCEEDED");

    // 선택 해제 → 다시 선택·수량 5
    Res unselect =
        call(
            HttpMethod.PATCH,
            "/carts/" + guestCart + "/items/" + guestItem,
            guest,
            Map.of("selected", false, "expectedVersion", 1),
            null,
            Map.of());
    assertThat(unselect.body().path("cartVersion").asInt()).isEqualTo(2);
    assertThat(unselect.body().path("cart").path("selectedItemAmount").asInt()).isZero();
    // 최소주문 안내(0.2.0): 선택 0원 → 부족액 = 최소 15,000 전부
    assertThat(unselect.body().path("cart").path("minOrderAmount").asInt()).isEqualTo(15_000);
    assertThat(unselect.body().path("cart").path("minOrderShortfall").asInt()).isEqualTo(15_000);
    assertThat(get("/carts/" + guestCart, guest).body().path("selectedItemAmount").asInt())
        .isZero();
    String patchKey = UUID.randomUUID().toString();
    call(
        HttpMethod.PATCH,
        "/carts/" + guestCart + "/items/" + guestItem,
        guest,
        Map.of("selected", true, "quantity", 5, "expectedVersion", 2),
        patchKey,
        Map.of());
    JsonNode cart = get("/carts/" + guestCart, guest).body();
    assertThat(cart.path("cartVersion").asInt()).isEqualTo(3);
    // 결과 조회도 지금의 장바구니를 준다
    assertThat(
            get("/carts/" + guestCart + "/change-requests/" + patchKey, guest)
                .body()
                .path("cart")
                .path("cartVersion")
                .asInt())
        .isEqualTo(3);
    JsonNode display = cart.path("items").path(0).path("display");
    assertThat(display.path("name").asString()).isEqualTo("[시험] 국산콩 두부 300g");
    assertThat(display.path("unitPrice").asInt()).isEqualTo(1900);
    assertThat(display.path("saleState").asString()).isEqualTo("ON_SALE");
    // 가용: 담당 MFC(열 때 준 MFC-SEED-01) 기준 사본. 담은 5개보다 적으면 부족(LOW, 수량 공개)
    stock("E-00000001", 3, true);
    display = get("/carts/" + guestCart, guest).body().path("items").path(0).path("display");
    assertThat(display.path("availability").asString()).isEqualTo("LOW");
    assertThat(display.path("availableQty").asInt()).isEqualTo(3);
    deps.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/internal/availability"))
            .withQueryParam("mfcId", equalTo("MFC-SEED-01"))
            .withQueryParam("sku", equalTo("E-00000001"))
            .withoutHeader("Authorization") // 서비스 간 인증 없음(ADR-0026)
            .withHeader("X-Source-System", equalTo("cart-service")));
    stock("E-00000001", 10, true);
    display = get("/carts/" + guestCart, guest).body().path("items").path(0).path("display");
    assertThat(display.path("availability").asString()).isEqualTo("AVAILABLE");
    assertThat(display.path("availableQty").isNull()).as("충분하면 수량 비공개").isTrue();
    stock("E-00000001", 0, true);
    assertThat(
            get("/carts/" + guestCart, guest)
                .body()
                .path("items")
                .path(0)
                .path("display")
                .path("availability")
                .asString())
        .isEqualTo("SOLD_OUT");
    stock("E-00000001", 10, false);
    assertThat(
            get("/carts/" + guestCart, guest)
                .body()
                .path("items")
                .path(0)
                .path("display")
                .path("availability")
                .asString())
        .isEqualTo("NOT_CARRIED");
    // 재고 조회 실패 → 불명(0 아님), 출처 UNAVAILABLE. 화면 전체는 실패시키지 않는다
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/availability"))
            .willReturn(aResponse().withStatus(503)));
    cart = get("/carts/" + guestCart, guest).body();
    display = cart.path("items").path(0).path("display");
    assertThat(display.path("availability").asString()).as("불명, 0 아님").isEqualTo("UNKNOWN");
    assertThat(display.path("name").asString()).isEqualTo("[시험] 국산콩 두부 300g");
    assertThat(cart.path("sources").toString()).contains("mfc-inventory-service", "UNAVAILABLE");
    // 출하 가능 판정 불가(shippableAvailable null — 최소 잔여일 미설정) → 불명. available(10)로 바꾸지 않는다(2026-11-18
    // IV-1)
    stockItem("E-00000001", 10, null, true);
    display = get("/carts/" + guestCart, guest).body().path("items").path(0).path("display");
    assertThat(display.path("availability").asString()).as("판정 불가 = 불명").isEqualTo("UNKNOWN");
    stock("E-00000001", 10, true);
    assertThat(cart.path("selectedItemAmount").asInt()).isEqualTo(9500);
    assertThat(cart.path("expiresAt").asString()).isNotBlank();
    // 최소주문 안내(0.2.0): 15,000 − 9,500 = 5,500 더 담기(BR-14, 배달팁·봉투값 제외)
    assertThat(cart.path("minOrderAmount").asInt()).isEqualTo(15_000);
    assertThat(cart.path("minOrderShortfall").asInt()).isEqualTo(5_500);
    // 가격 조회 실패 → 선택 금액·부족액 모두 불명(null, 0 아님). 최소 금액은 그대로, 화면은 실패시키지 않는다
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/prices")).willReturn(aResponse().withStatus(503)));
    JsonNode noPrice = get("/carts/" + guestCart, guest).body();
    assertThat(noPrice.path("selectedItemAmount").isNumber()).as("가격 불명 = 합계 없음").isFalse();
    assertThat(noPrice.path("minOrderShortfall").isNumber()).as("가격 불명 = 부족액 불명").isFalse();
    assertThat(noPrice.path("minOrderAmount").asInt()).isEqualTo(15_000);
    json(
        WireMock.get(urlPathEqualTo("/api/v1/prices")),
        Map.of(
            "items", List.of(Map.of("sku", "E-00000001", "basePrice", 1900, "priceVersion", 1))));
    // 변경 원결과 조회
    assertThat(
            get("/carts/" + guestCart + "/change-requests/" + patchKey, guest)
                .body()
                .path("quantity")
                .asInt())
        .isEqualTo(5);
    assertThat(get("/carts/" + guestCart + "/change-requests/none", guest).status()).isEqualTo(404);
    // 남의 장바구니
    assertThat(get("/carts/" + guestCart, member).body().path("code").asString())
        .isEqualTo("FORBIDDEN_OWNER");

    // 회원 장바구니: E-1 6개
    String memberCart = post("/carts", member, null).body().path("cartId").asString();
    String memberItem =
        add(member, memberCart, "E-00000001", 6, 0, null).body().path("cartItemId").asString();

    Map<String, Object> mergeBody = new LinkedHashMap<>();
    mergeBody.put("sourceCartId", guestCart);
    mergeBody.put("expectedVersion", 1);
    mergeBody.put(
        "items", List.of(Map.of("sourceItemId", guestItem, "sku", "E-00000001", "quantity", 5)));
    // 6 + 5 = 11 → 상한 위반 → 전부 거절, 버전 그대로
    Res rejected =
        call(
            HttpMethod.POST,
            "/carts/" + memberCart + "/merges",
            member,
            mergeBody,
            null,
            Map.of("X-Guest-Token", guest));
    assertThat(rejected.status()).isEqualTo(200);
    assertThat(rejected.body().path("state").asString()).isEqualTo("MERGE_REJECTED");
    assertThat(rejected.body().path("items").path(0).path("reasonCode").asString())
        .isEqualTo("QUANTITY_LIMIT_EXCEEDED");
    assertThat(rejected.body().path("targetCartVersion").asInt()).isEqualTo(1);

    // 회원 수량 2로 → 병합(+ 원본에 없는 항목은 거절) → 부분 성공
    call(
        HttpMethod.PATCH,
        "/carts/" + memberCart + "/items/" + memberItem,
        member,
        Map.of("quantity", 2, "expectedVersion", 1),
        null,
        Map.of());
    mergeBody.put("expectedVersion", 2);
    mergeBody.put(
        "items",
        List.of(
            Map.of("sourceItemId", guestItem, "sku", "E-00000001", "quantity", 5),
            Map.of("sourceItemId", "CI-none", "sku", "E-00000009", "quantity", 1)));
    // 소유 증명 실패: 회원 토큰을 X-Guest-Token 으로
    assertThat(
            call(
                    HttpMethod.POST,
                    "/carts/" + memberCart + "/merges",
                    member,
                    mergeBody,
                    null,
                    Map.of("X-Guest-Token", member))
                .body()
                .path("code")
                .asString())
        .isEqualTo("FORBIDDEN_OWNER");
    String mergeKey = UUID.randomUUID().toString();
    Res merged =
        call(
            HttpMethod.POST,
            "/carts/" + memberCart + "/merges",
            member,
            mergeBody,
            mergeKey,
            Map.of("X-Guest-Token", guest));
    assertThat(merged.body().path("state").asString()).isEqualTo("MERGE_PARTIAL");
    assertThat(merged.body().path("sourceCleared").asString()).isEqualTo("DONE");
    assertThat(merged.body().path("items").path(0).path("result").asString()).isEqualTo("MERGED");
    assertThat(merged.body().path("items").path(1).path("reasonCode").asString())
        .isEqualTo("SOURCE_ITEM_CHANGED");
    String mergeId = merged.body().path("mergeId").asString();
    // 재전송 → 같은 결과(중복 합산 없음)
    Res again =
        call(
            HttpMethod.POST,
            "/carts/" + memberCart + "/merges",
            member,
            mergeBody,
            mergeKey,
            Map.of("X-Guest-Token", guest));
    assertThat(again.body().path("mergeId").asString()).isEqualTo(mergeId);
    JsonNode memberView = get("/carts/" + memberCart, member).body();
    assertThat(memberView.path("items").path(0).path("quantity").asInt()).isEqualTo(7);
    assertThat(memberView.path("cartVersion").asInt()).isEqualTo(3);
    assertThat(get("/carts/" + guestCart, guest).body().path("items")).isEmpty();
    assertThat(
            get("/carts/" + memberCart + "/merges/" + mergeId, member)
                .body()
                .path("state")
                .asString())
        .isEqualTo("MERGE_PARTIAL");
    // 비회원은 병합 불가
    assertThat(
            call(
                    HttpMethod.POST,
                    "/carts/" + guestCart + "/merges",
                    guest,
                    mergeBody,
                    null,
                    Map.of("X-Guest-Token", guest))
                .status())
        .isEqualTo(403);

    // 주문용 내부 조회(서비스 토큰)
    Res selected =
        get(
            "/internal/carts/" + memberCart + "/selected-items?accountId=" + subject(member),
            SERVICE_TOKEN);
    assertThat(selected.status()).isEqualTo(200);
    assertThat(selected.body().path("items").path(0).path("quantity").asInt()).isEqualTo(7);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'CartItemChangeApplied'"
                    + " AND topic = 'commerce.cart.v1' AND aggregateid = ?",
                Integer.class,
                guestCart))
        .isEqualTo(3);
  }

  void orderConfirmed(UUID eventId, String accountId, String orderId, Map<String, Object> line) {
    String json =
        EventEnvelopeFixtures.envelope("OrderConfirmed")
            .eventId(eventId)
            .from("order-service")
            .aggregate("Order", orderId, 1)
            .payload(Map.of("orderId", orderId, "accountId", accountId, "lines", List.of(line)))
            .buildJson();
    kafka.send("order.order.v1", orderId, json).join();
  }

  void awaitInbox(UUID eventId) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM inbox_event WHERE event_id = ?", Integer.class, eventId);
      if (n != null && n > 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("OrderConfirmed 미반영 " + eventId);
  }

  /** OrderConfirmed → 주문분만 장바구니에서 뺀다(cartItemId, 없으면 SKU). 같은 사건 재전달은 한 번만. */
  @Test
  void orderConfirmedRemovesOrderedItems() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String member = TestJwt.customer(account);
    String cartId = post("/carts", member, null).body().path("cartId").asString();
    Res added = add(member, cartId, "E-00000001", 5, 0, null);
    assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
    String itemId = added.body().path("cartItemId").asString();

    // 주문 3개(cartItemId) → 2개 남음, 버전 +1
    UUID e1 = UUID.randomUUID();
    String order1 = "ORD-" + UUID.randomUUID();
    orderConfirmed(
        e1, account, order1, Map.of("cartItemId", itemId, "sku", "E-00000001", "qty", 3));
    awaitInbox(e1);
    JsonNode cart = get("/carts/" + cartId, member).body();
    assertThat(cart.path("cartVersion").asInt()).isEqualTo(2);
    assertThat(cart.path("items").path(0).path("quantity").asInt()).isEqualTo(2);
    // 같은 사건 재전달 → inbox 가 막는다(수량·버전 그대로). 같은 키(같은 파티션)의 표지 사건으로 처리 순서를 확인
    orderConfirmed(
        e1, account, order1, Map.of("cartItemId", itemId, "sku", "E-00000001", "qty", 3));
    UUID marker = UUID.randomUUID();
    orderConfirmed(marker, account, order1, Map.of("sku", "E-00000404", "qty", 1));
    awaitInbox(marker);
    JsonNode same = get("/carts/" + cartId, member).body();
    assertThat(same.path("cartVersion").asInt()).isEqualTo(2);
    assertThat(same.path("items").path(0).path("quantity").asInt()).isEqualTo(2);
    // 다음 주문(cartItemId 없음 → SKU) 2개 → 삭제
    UUID e2 = UUID.randomUUID();
    String order2 = "ORD-" + UUID.randomUUID();
    orderConfirmed(e2, account, order2, Map.of("sku", "E-00000001", "qty", 2));
    awaitInbox(e2);
    JsonNode after = get("/carts/" + cartId, member).body();
    assertThat(after.path("items")).isEmpty();
    assertThat(after.path("cartVersion").asInt()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'CartItemChangeApplied'"
                    + " AND aggregateid = ? AND payload->'payload'->>'appliedRequestId' LIKE 'OrderConfirmed:%'",
                Integer.class, cartId))
        .isEqualTo(2);
  }

  String subject(String jwt) {
    return mapper
        .readTree(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]))
        .path("sub")
        .asString();
  }
}
