package com.pmart.customer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
import java.util.function.Consumer;
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
 * 고객 프로필(CustomerAuthenticated) → 주소록(권역 판정 사본) → 현재 쇼핑 주소(ShoppingAddressChanged) → 비회원 배송지 → 내부
 * 조회. 실제 PostgreSQL·Kafka, delivery-serviceability 는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.customer.address-limit=4"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "serviceability",
        baseUrlProperties = "pmart.customer.serviceability-base-url"))
class CustomerAddressFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("serviceability")
  WireMockServer serviceability;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    serviceability.resetAll();
    stubCheck(
        "IN",
        "{\"checkId\":\"SC-IN\",\"result\":\"SERVICEABLE\",\"mfcId\":\"MFC-BSN-01\",\"zoneId\":\"Z-1\",\"mappingVersion\":3,\"evaluatedAt\":\"2026-10-30T01:00:00Z\"}");
    stubCheck(
        "OUT",
        "{\"checkId\":\"SC-OUT\",\"result\":\"OUT_OF_AREA\",\"mappingVersion\":3,\"evaluatedAt\":\"2026-10-30T01:00:00Z\"}");
    stubCheck(
        "OVERLAP",
        "{\"checkId\":\"SC-OV\",\"result\":\"OVERLAP\",\"mappingVersion\":3,\"evaluatedAt\":\"2026-10-30T01:00:00Z\"}");
    serviceability.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/serviceability-checks"))
            .withRequestBody(matchingJsonPath("$.address.roadAddress", equalTo("DOWN")))
            .willReturn(aResponse().withStatus(503)));
  }

  void stubCheck(String road, String body) {
    serviceability.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/serviceability-checks"))
            .withRequestBody(matchingJsonPath("$.address.roadAddress", equalTo(road)))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  record Res(int status, JsonNode body) {}

  Res call(HttpMethod method, String path, Object body, Consumer<HttpHeaders> headers) {
    var spec = http.method(method).uri(path).headers(headers);
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    var e = spec.retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res send(
      String token, HttpMethod method, String path, Object body, String key, Integer expected) {
    return call(
        method,
        path,
        body,
        h -> {
          h.setBearerAuth(token);
          h.set("Idempotency-Key", key);
          h.set("X-Source-System", "it");
          if (expected != null) {
            h.set("X-Expected-Version", String.valueOf(expected));
          }
        });
  }

  Res send(String token, HttpMethod method, String path, Object body) {
    return send(token, method, path, body, UUID.randomUUID().toString(), null);
  }

  Res get(String token, String path) {
    return call(HttpMethod.GET, path, null, h -> h.setBearerAuth(token));
  }

  static Map<String, Object> address(String road) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("label", "집");
    a.put("roadAddress", road);
    a.put("detailAddress", "101동 1001호");
    a.put("receiverName", "홍길동");
    a.put("receiverPhone", "010-1234-5678");
    a.put("entranceMethod", "PASSWORD");
    a.put("entranceNote", "#1234");
    a.put("geo", Map.of("lat", 37.50, "lng", 127.02));
    return a;
  }

  void authenticated(String accountId) throws InterruptedException {
    String json =
        EventEnvelopeFixtures.envelope("CustomerAuthenticated")
            .from("auth-service")
            .aggregate("Account", accountId, 1)
            .payload(
                Map.of(
                    "accountId",
                    accountId,
                    "provider",
                    "GOOGLE",
                    "authenticatedAt",
                    "2026-10-30T00:00:00Z"))
            .buildJson();
    kafka.send("platform.auth.v1", accountId, json).join();
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM customer_profile WHERE account_id = ? AND social_provider = 'GOOGLE'",
              Integer.class,
              accountId);
      if (n != null && n > 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("CustomerAuthenticated 미반영");
  }

  @Test
  void profileAddressesShoppingContextAndGuest() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(cu);
    authenticated(cu);

    Res profile = get(me, "/me");
    assertThat(profile.status()).isEqualTo(200);
    assertThat(profile.body().path("socialProvider").asString()).isEqualTo("GOOGLE");
    assertThat(profile.body().path("status").asString()).isEqualTo("ACTIVE");
    assertThat(profile.body().path("joinedAt").asString()).startsWith("2026-10-30T00:00");
    // 운영자 토큰은 고객 API 불가
    assertThat(get(TestJwt.operator("ops-1", List.of("OPS"), List.of()), "/me").status())
        .isEqualTo(403);

    // 주소 추가: 판정 사본(안·밖·판정 불가)
    String key = UUID.randomUUID().toString();
    Res in = send(me, HttpMethod.POST, "/me/addresses", address("IN"), key, null);
    assertThat(in.status()).isEqualTo(201);
    String inId = in.body().path("addressId").asString();
    assertThat(in.body().path("serviceability").path("state").asString()).isEqualTo("SERVICEABLE");
    assertThat(in.body().path("serviceability").path("mfcId").asString()).isEqualTo("MFC-BSN-01");
    assertThat(in.body().path("serviceability").path("serviceabilityVersion").asInt()).isEqualTo(3);
    assertThat(in.body().path("geo").path("lat").asDouble()).isEqualTo(37.50);
    // 같은 키 재전송 → 같은 주소
    assertThat(
            send(me, HttpMethod.POST, "/me/addresses", address("IN"), key, null)
                .body()
                .path("addressId")
                .asString())
        .isEqualTo(inId);
    // 회원 호출은 본인 토큰을 전파
    serviceability.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/serviceability-checks"))
            .withHeader("Authorization", matching("Bearer .+"))
            .withRequestBody(matchingJsonPath("$.purpose", equalTo("ADDRESS_BOOK"))));

    Res out = send(me, HttpMethod.POST, "/me/addresses", address("OUT"));
    assertThat(out.body().path("serviceability").path("state").asString())
        .isEqualTo("OUT_OF_SERVICE_AREA");
    Res down = send(me, HttpMethod.POST, "/me/addresses", address("DOWN"));
    assertThat(down.status()).as("판정 실패여도 주소는 저장").isEqualTo(201);
    assertThat(down.body().path("serviceability").path("state").asString()).isEqualTo("UNKNOWN");
    assertThat(down.body().path("serviceability").path("asOf").path("freshness").asString())
        .isEqualTo("UNAVAILABLE");
    Res overlap = send(me, HttpMethod.POST, "/me/addresses", address("OVERLAP"));
    assertThat(overlap.body().path("serviceability").path("state").asString()).isEqualTo("UNKNOWN");
    assertThat(
            overlap.body().path("serviceability").path("mfcId").isMissingNode()
                || overlap.body().path("serviceability").path("mfcId").isNull())
        .isTrue();
    // 최대 건수(시험값 4)
    Res over = send(me, HttpMethod.POST, "/me/addresses", address("IN"));
    assertThat(over.status()).isEqualTo(422);
    assertThat(over.body().path("code").asString()).isEqualTo("ADDRESS_LIMIT_EXCEEDED");

    Res list = get(me, "/me/addresses");
    assertThat(list.body().path("items")).hasSize(4);
    assertThat(list.body().path("shoppingContext").path("shoppingContextVersion").asInt()).isZero();

    // 다른 고객은 볼 수 없다
    assertThat(get(TestJwt.customer("CU-OTHER"), "/me/addresses/" + inId).status()).isEqualTo(404);

    // 쇼핑 주소 선택 → 버전 1, 담당 MFC, 이벤트
    Res selected =
        send(me, HttpMethod.POST, "/me/shopping-address-selections", Map.of("addressId", inId));
    assertThat(selected.status()).isEqualTo(200);
    assertThat(selected.body().path("shoppingContextVersion").asInt()).isEqualTo(1);
    assertThat(selected.body().path("mfcId").asString()).isEqualTo("MFC-BSN-01");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ShoppingAddressChanged'"
                    + " AND topic = 'commerce.customer.v1' AND aggregateid = ?",
                Integer.class,
                cu))
        .isEqualTo(1);
    // 낡은 기대 버전 → 409
    assertThat(
            send(
                    me,
                    HttpMethod.POST,
                    "/me/shopping-address-selections",
                    Map.of("addressId", inId),
                    UUID.randomUUID().toString(),
                    0)
                .status())
        .isEqualTo(409);
    assertThat(get(me, "/me/shopping-context").body().path("shoppingAddressId").asString())
        .isEqualTo(inId);

    // 현재 쇼핑 주소는 삭제 불가, 다른 주소는 삭제
    Res del = send(me, HttpMethod.DELETE, "/me/addresses/" + inId, null);
    assertThat(del.status()).isEqualTo(409);
    assertThat(del.body().path("code").asString()).isEqualTo("INVALID_STATE");
    String outId = out.body().path("addressId").asString();
    assertThat(send(me, HttpMethod.DELETE, "/me/addresses/" + outId, null).status()).isEqualTo(204);

    // 현재 쇼핑 주소 수정 → 재판정, 컨텍스트 버전 증가
    assertThat(
            send(
                    me,
                    HttpMethod.PATCH,
                    "/me/addresses/" + inId,
                    address("OUT"),
                    UUID.randomUUID().toString(),
                    5)
                .status())
        .isEqualTo(409);
    Res updated =
        send(
            me,
            HttpMethod.PATCH,
            "/me/addresses/" + inId,
            address("OUT"),
            UUID.randomUUID().toString(),
            0);
    assertThat(updated.status()).isEqualTo(200);
    assertThat(updated.body().path("version").asInt()).isEqualTo(1);
    assertThat(updated.body().path("serviceability").path("state").asString())
        .isEqualTo("OUT_OF_SERVICE_AREA");
    JsonNode ctx = get(me, "/me/shopping-context").body();
    assertThat(ctx.path("shoppingContextVersion").asInt()).isEqualTo(2);
    assertThat(ctx.path("mfcId").isMissingNode() || ctx.path("mfcId").isNull()).isTrue();

    // 내부 조회(서비스 토큰)
    String svc = TestJwt.service("order-service");
    Res status = get(svc, "/internal/accounts/" + cu + "/status");
    assertThat(status.status()).isEqualTo(200);
    assertThat(status.body().path("purchasable").asBoolean()).isTrue();
    // 첫 구매 대조: 사용 기록 없음 → 후보
    assertThat(
            get(svc, "/internal/accounts/" + cu + "/first-purchase-checks")
                .body()
                .path("firstPurchaseCandidate")
                .asBoolean())
        .isTrue();
    Res internalCtx = get(svc, "/internal/accounts/" + cu + "/shopping-context");
    assertThat(internalCtx.body().path("address").path("addressId").asString()).isEqualTo(inId);
    assertThat(internalCtx.body().path("phoneRef").asString()).isEqualTo("010-****-5678");
    assertThat(get(svc, "/internal/accounts/CU-NONE/status").status()).isEqualTo(404);
  }

  @Test
  void guestDeliveryAddressIsBoundToDevice() {
    String device = "DEV-" + UUID.randomUUID();
    Res created =
        call(
            HttpMethod.POST,
            "/guest-delivery-addresses",
            address("IN"),
            h -> {
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "customer-app");
              h.set("X-Device-Id", device);
            });
    assertThat(created.status()).isEqualTo(201);
    String id = created.body().path("addressId").asString();
    assertThat(created.body().path("serviceability").path("state").asString())
        .isEqualTo("SERVICEABLE");
    // 비회원 판정은 익명 호출
    serviceability.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/serviceability-checks"))
            .withHeader("Authorization", absent()));

    assertThat(
            call(
                    HttpMethod.GET,
                    "/guest-delivery-addresses/" + id,
                    null,
                    h -> h.set("X-Device-Id", device))
                .status())
        .isEqualTo(200);
    assertThat(
            call(
                    HttpMethod.GET,
                    "/guest-delivery-addresses/" + id,
                    null,
                    h -> h.set("X-Device-Id", "DEV-OTHER"))
                .status())
        .isEqualTo(403);
  }
}
