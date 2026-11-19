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
      "pmart.customer.address-limit=4",
      // 꺼진 경로(구매 가능 = ACTIVE 만) — 켠 경로는 ConsentFlowIT. LAN 설정은 2026-11-19 부터 켬(CU-1)
      "pmart.customer.consents.purchase-gate=false"
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
    authenticated(accountId, "2026-10-30T00:00:00Z", "홍길동", accountId + "@gmail.com");
  }

  /** Google 프로필(ADR-0024)을 실은 CustomerAuthenticated 를 보내고 그 인증 시각까지 반영되기를 기다린다. */
  void authenticated(String accountId, String at, String name, String email)
      throws InterruptedException {
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
                    at,
                    "email",
                    email,
                    "emailVerified",
                    true,
                    "name",
                    name,
                    "pictureUrl",
                    "https://lh3.googleusercontent.com/a/" + accountId))
            .buildJson();
    kafka.send("platform.auth.v1", accountId, json).join();
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM inbox_event WHERE aggregate_id = ? AND event_type ="
                  + " 'CustomerAuthenticated' AND event_id = ?::uuid",
              Integer.class,
              accountId,
              mapper.readTree(json).path("eventId").asString());
      if (n != null && n > 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("CustomerAuthenticated 미반영");
  }

  @Test
  void googleProfileIsKeptAndLateOldLoginDoesNotRollBack() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(cu);
    authenticated(cu, "2026-10-30T00:00:00Z", "홍길동", "old@gmail.com");
    Res first = get(me, "/me");
    assertThat(first.body().path("name").asString()).isEqualTo("홍길동");
    assertThat(first.body().path("email").asString()).isEqualTo("old@gmail.com");
    assertThat(first.body().path("emailVerified").asBoolean()).isTrue();
    // 사진 사본은 저장소(S3)가 있을 때만 — 이 시험엔 버킷이 없어 제공자 URL 을 그대로 내주지 않는다(ProfileImageIT)
    assertThat(
            first.body().path("profileImageUrl").isMissingNode()
                || first.body().path("profileImageUrl").isNull())
        .isTrue();

    // 다음 로그인: 구글에서 바뀐 이름·이메일로 갱신
    authenticated(cu, "2026-10-31T00:00:00Z", "홍길순", "new@gmail.com");
    assertThat(get(me, "/me").body().path("name").asString()).isEqualTo("홍길순");

    // 늦게 도착한 옛 로그인 사건은 최신 값을 되돌리지 않는다
    authenticated(cu, "2026-10-29T00:00:00Z", "옛이름", "older@gmail.com");
    Res after = get(me, "/me");
    assertThat(after.body().path("name").asString()).isEqualTo("홍길순");
    assertThat(after.body().path("email").asString()).isEqualTo("new@gmail.com");
    assertThat(after.body().path("joinedAt").asString()).startsWith("2026-10-29");
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
    assertThat(in.body().path("geoSource").asString()).as("고객 핀").isEqualTo("PIN");
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
    // 구매 차단 스위치(purchase-gate)가 꺼져 있으면(이 시험은 속성으로 끔 — LAN 은 2026-11-19 켬) 동의 전에도 지금처럼 ACTIVE 만으로 구매
    // 가능.
    // 동의가 빠진 사실은 requiredConsentOutdated 로 알린다. 켠 경로는 ConsentFlowIT
    assertThat(status.body().path("purchasable").asBoolean()).isTrue();
    assertThat(status.body().path("requiredConsentOutdated").asBoolean()).isTrue();
    List<Map<String, Object>> required = new java.util.ArrayList<>();
    for (String[] c :
        new String[][] {
          {"TERMS_OF_SERVICE", "2026-11-17"}, {"PRIVACY_POLICY", "2026-11-17"}, {"AGE_OVER_14", "1"}
        }) {
      required.add(Map.of("consentType", c[0], "version", c[1], "agreed", true));
    }
    assertThat(
            send(me, HttpMethod.POST, "/me/consent-changes", Map.of("changes", required)).status())
        .isEqualTo(200);
    status = get(svc, "/internal/accounts/" + cu + "/status"); // 동의 뒤: 그대로 가능, 빠진 것 없음
    assertThat(status.body().path("purchasable").asBoolean()).isTrue();
    assertThat(status.body().path("requiredConsentOutdated").asBoolean()).isFalse();
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

  /**
   * 배송지 좌표 채우기(0.5.0, 2026-11-18 — 클라이언트 요청 rider-app drop-exact-coordinates 2): 핀(geo) 없이 저장하면 서버가
   * serviceability `/internal/address-geocodes` 로 도로명주소 좌표를 채우고 geoSource GEOCODE(같은 도로명 결과를 고름),
   * 변환 실패면 예전처럼 좌표 없이 저장. 같은 키 재전송은 처음 결과.
   */
  @Test
  void addressWithoutPinGetsGeocodedCoordinates() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(cu);
    authenticated(cu);
    serviceability.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/address-geocodes"))
            .withRequestBody(matchingJsonPath("$.query", equalTo("IN")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"results\":[{\"roadAddress\":\"IN 근처\",\"lat\":35.0,\"lng\":129.0},"
                            + "{\"roadAddress\":\"IN\",\"lat\":35.1579,\"lng\":129.0594}]}")));
    Map<String, Object> noPin = address("IN");
    noPin.remove("geo");
    String key = UUID.randomUUID().toString();
    Res filled = send(me, HttpMethod.POST, "/me/addresses", noPin, key, null);
    assertThat(filled.status()).as(filled.body().toString()).isEqualTo(201);
    assertThat(filled.body().path("geo").path("lat").asDouble()).isEqualTo(35.1579);
    assertThat(filled.body().path("geo").path("lng").asDouble()).isEqualTo(129.0594);
    assertThat(filled.body().path("geoSource").asString()).isEqualTo("GEOCODE");
    assertThat(
            send(me, HttpMethod.POST, "/me/addresses", noPin, key, null)
                .body()
                .path("addressId")
                .asString())
        .isEqualTo(filled.body().path("addressId").asString());
    serviceability.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/address-geocodes"))
            .withHeader("X-Source-System", equalTo("customer-service")));

    // 변환 실패(스텁 없음 → 404) — 좌표 없이 저장, geoSource 없음
    Map<String, Object> unknownRoad = address("OUT");
    unknownRoad.remove("geo");
    Res plain =
        send(me, HttpMethod.POST, "/me/addresses", unknownRoad, UUID.randomUUID().toString(), null);
    assertThat(plain.status()).isEqualTo(201);
    assertThat(plain.body().path("geo").isMissingNode() || plain.body().path("geo").isNull())
        .isTrue();
    assertThat(
            plain.body().path("geoSource").isMissingNode()
                || plain.body().path("geoSource").isNull())
        .isTrue();
  }

  /** BR-60(0.8.0, 2026-11-19): 받는 분·연락처 없이 저장, PATCH 에서 빠지면 그대로, 주문용 내부 컨텍스트는 프로필로 채운다. */
  @Test
  void receiverIsOptionalAndOrderContextFallsBackToProfile() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(cu);
    authenticated(cu); // 제공자 이름 홍길동, 별명·연락처 없음
    Map<String, Object> bare = address("BR60");
    bare.remove("receiverName");
    bare.remove("receiverPhone");
    Res saved = send(me, HttpMethod.POST, "/me/addresses", bare);
    assertThat(saved.status()).as(saved.body().toString()).isEqualTo(201);
    String addressId = saved.body().path("addressId").asString();
    assertThat(
            saved.body().path("receiverName").isNull()
                || saved.body().path("receiverName").isMissingNode())
        .isTrue();

    String svc = TestJwt.service("order-service");
    String ctxPath =
        "/internal/accounts/" + cu + "/shopping-context?shoppingAddressId=" + addressId;
    JsonNode ctx = get(svc, ctxPath).body();
    assertThat(ctx.path("address").path("receiverName").asString()).isEqualTo("홍길동");
    assertThat(
            ctx.path("address").path("receiverPhone").isNull()
                || ctx.path("address").path("receiverPhone").isMissingNode())
        .as("프로필에도 연락처가 없으면 비어 있다(order 가 CONTACT_REQUIRED)")
        .isTrue();

    assertThat(
            send(me, HttpMethod.POST, "/me/profile-changes", Map.of("phone", "010-9876-5432"))
                .status())
        .isEqualTo(200);
    ctx = get(svc, ctxPath).body();
    assertThat(ctx.path("address").path("receiverPhone").asString()).isEqualTo("01098765432");
    assertThat(ctx.path("phoneRef").asString()).isEqualTo("010-****-5432");

    // 주소에 값이 있으면 그것이 먼저, PATCH 에서 빠뜨리면 그대로 둔다
    Res withReceiver =
        send(
            me,
            HttpMethod.PATCH,
            "/me/addresses/" + addressId,
            address("BR60"),
            UUID.randomUUID().toString(),
            saved.body().path("version").asInt());
    assertThat(withReceiver.status()).as(withReceiver.body().toString()).isEqualTo(200);
    Map<String, Object> labelOnly = new LinkedHashMap<>(bare);
    labelOnly.put("label", "회사");
    Res kept =
        send(
            me,
            HttpMethod.PATCH,
            "/me/addresses/" + addressId,
            labelOnly,
            UUID.randomUUID().toString(),
            withReceiver.body().path("version").asInt());
    assertThat(kept.status()).as(kept.body().toString()).isEqualTo(200);
    assertThat(kept.body().path("label").asString()).isEqualTo("회사");
    assertThat(kept.body().path("receiverName").asString()).isEqualTo("홍길동");
    assertThat(kept.body().path("receiverPhone").asString()).isEqualTo("010-1234-5678");
    assertThat(get(svc, ctxPath).body().path("address").path("receiverPhone").asString())
        .isEqualTo("010-1234-5678");
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

  void orderConfirmed(UUID eventId, String accountId, String orderId, boolean firstPurchase)
      throws InterruptedException {
    String json =
        EventEnvelopeFixtures.envelope("OrderConfirmed")
            .eventId(eventId)
            .from("order-service")
            .aggregate("Order", orderId, 1)
            .payload(
                Map.of(
                    "orderId",
                    orderId,
                    "accountId",
                    accountId,
                    "firstPurchase",
                    firstPurchase,
                    "confirmedAt",
                    "2026-10-31T01:00:00Z"))
            .buildJson();
    kafka.send("order.order.v1", orderId, json).join();
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

  /**
   * OrderConfirmed: 첫 구매 무료를 적용한 주문만 사용 기록 → first-purchase-checks 후보 아님. 재전달·다른 주문은 기록을 바꾸지 않는다.
   */
  @Test
  void orderConfirmedMarksFirstPurchaseUsed() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String svc = TestJwt.service("order-service");
    String check = "/internal/accounts/" + cu + "/first-purchase-checks";

    // 첫 구매 무료를 쓰지 않은 주문(30,000 이상 등) → 여전히 후보
    orderConfirmed(UUID.randomUUID(), cu, "ORD-A", false);
    assertThat(get(svc, check).body().path("firstPurchaseCandidate").asBoolean()).isTrue();

    // 첫 구매 무료 적용 주문 → 후보 아님
    UUID used = UUID.randomUUID();
    orderConfirmed(used, cu, "ORD-B", true);
    assertThat(get(svc, check).body().path("firstPurchaseCandidate").asBoolean()).isFalse();
    // 다른 주문이 또 첫 구매로 와도(동시 주문 경합) 처음 쓴 주문을 남긴다
    orderConfirmed(UUID.randomUUID(), cu, "ORD-C", true);
    assertThat(
            jdbc.queryForObject(
                "SELECT order_id FROM first_purchase_used WHERE account_id = ?", String.class, cu))
        .isEqualTo("ORD-B");
  }

  void orderCancelled(String accountId, String orderId, String scope, boolean firstPurchase)
      throws InterruptedException {
    UUID eventId = UUID.randomUUID();
    String json =
        EventEnvelopeFixtures.envelope("OrderCancelled")
            .eventId(eventId)
            .from("order-service")
            .aggregate("Order", orderId, 2)
            .payload(
                Map.of(
                    "orderId",
                    orderId,
                    "scope",
                    scope,
                    "lines",
                    List.of(Map.of("orderLineId", orderId + "-L1", "qty", 1)),
                    "reason",
                    "CANCEL_BEFORE_SHIP",
                    "accountId",
                    accountId,
                    "firstPurchase",
                    firstPurchase))
            .buildJson();
    kafka.send("order.order.v1", orderId, json).join();
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
    throw new AssertionError("OrderCancelled 미반영 " + eventId);
  }

  /**
   * OrderCancelled: 첫 구매 혜택을 쓴 주문이 출고 전 전체 취소되면 자격을 되돌린다(OMS 175행). 부분 취소·다른 주문의 취소는 기록을 지우지 않는다.
   */
  @Test
  void fullCancelRestoresFirstPurchase() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String svc = TestJwt.service("order-service");
    String check = "/internal/accounts/" + cu + "/first-purchase-checks";
    orderConfirmed(UUID.randomUUID(), cu, "ORD-FP", true);
    assertThat(get(svc, check).body().path("firstPurchaseCandidate").asBoolean()).isFalse();

    // 다른 주문 취소·부분 취소 → 그대로
    orderCancelled(cu, "ORD-OTHER", "FULL", true);
    orderCancelled(cu, "ORD-FP", "PARTIAL", true);
    assertThat(get(svc, check).body().path("firstPurchaseCandidate").asBoolean()).isFalse();

    // 첫 구매 주문의 전체 취소 → 다시 후보
    orderCancelled(cu, "ORD-FP", "FULL", true);
    assertThat(get(svc, check).body().path("firstPurchaseCandidate").asBoolean()).isTrue();
  }
}
