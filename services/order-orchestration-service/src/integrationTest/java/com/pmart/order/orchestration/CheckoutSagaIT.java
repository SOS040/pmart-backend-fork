package com.pmart.order.orchestration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.orchestration.application.CheckoutSagaService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S1 a~f: 재고 예약 → 작업 여력 → 슬롯(예약배달) → 결제 시작 → 결제 대기. 거절이면 이번 시도분만 해제(원예약 조회로 확인), 자원 만료 뒤 미승인이면 해제.
 * 참여 정본·auth 는 한 WireMock(주문 ID 로 응답을 나눈다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${parts.url}/api/v1/internal/service-tokens",
      "pmart.orchestration.mfc-inventory-base-url=${parts.url}",
      "pmart.orchestration.mfc-capacity-base-url=${parts.url}",
      "pmart.orchestration.delivery-capacity-base-url=${parts.url}",
      "pmart.orchestration.payment-base-url=${parts.url}",
      "pmart.orchestration.sweep-interval=PT1H" // 시험이 sweep 을 직접 부른다
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "parts", baseUrlProperties = "parts.url"))
class CheckoutSagaIT extends PostgresKafkaContainerSupport {

  static final String SERVICE = TestJwt.service("order-service");
  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired CheckoutSagaService sagas;

  @InjectWireMock("parts")
  WireMockServer parts;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    parts.resetAll();
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "access_token",
                        TestJwt.service("order-orchestration-service"),
                        "token_type",
                        "Bearer",
                        "expires_in",
                        300))));
  }

  /** 주문 ID 별 정본 응답. reservationState 는 예약 조회가 돌려줄 상태(해제 확인용). */
  void participants(
      String orderId,
      Instant reservationExpires,
      String reservationState,
      ResponseDefinitionBuilder capacity) {
    String rid = "RSV-" + orderId;
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/reservations"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
            .willReturn(json(201, Map.of("status", "APPLIED", "requestId", "x", "resultId", rid))));
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/" + rid))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        rid,
                        "state",
                        reservationState,
                        "expiresAt",
                        reservationExpires.toString()))));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/reservations/" + rid + "/releases"))
            .willReturn(json(200, Map.of("status", "APPLIED", "requestId", "x"))));
    String hid = "CH-" + orderId;
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/capacity-holds"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
            .willReturn(capacity));
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/capacity-holds/" + hid))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "holdId",
                        hid,
                        "state",
                        reservationState,
                        "expiresAt",
                        reservationExpires.plusSeconds(30).toString()))));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/capacity-holds/" + hid + "/releases"))
            .willReturn(json(200, Map.of("status", "APPLIED", "requestId", "x"))));
    String sid = "SH-" + orderId;
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/slot-holds"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "holdId",
                        sid,
                        "state",
                        "HELD",
                        "expiresAt",
                        reservationExpires.plusSeconds(60).toString()))));
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/slot-holds/" + sid))
            .willReturn(json(200, Map.of("holdId", sid, "state", reservationState))));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/slot-holds/" + sid + "/releases"))
            .willReturn(json(200, Map.of("status", "APPLIED", "requestId", "x"))));
    String pid = "PAY-" + orderId;
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/payments"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "paymentAttemptId",
                        pid,
                        "state",
                        "CREATED",
                        "orderId",
                        orderId,
                        "amount",
                        18100,
                        "checkout",
                        Map.of(
                            "provider",
                            "TOSS_PAYMENTS",
                            "pgOrderId",
                            pid,
                            "clientKey",
                            "test_ck_docs",
                            "expiresAt",
                            reservationExpires.plusSeconds(120).toString())))));
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/payments/" + pid))
            .willReturn(json(200, Map.of("paymentAttemptId", pid, "state", "PENDING"))));
  }

  ResponseDefinitionBuilder capacityOk(String orderId) {
    return json(201, Map.of("status", "APPLIED", "requestId", "x", "resultId", "CH-" + orderId));
  }

  ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  Map<String, Object> request(String orderId, String mode, Instant quoteExpiresAt) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("draftId", "DRF-" + orderId);
    b.put("orderId", orderId);
    b.put("purchaseIntentId", "PI-" + orderId);
    b.put("customerRef", "CU-1");
    b.put("orderName", "[시험] 국산콩 두부 300g 외 1건");
    b.put("acceptedQuoteVersion", 1);
    b.put("quoteExpiresAt", quoteExpiresAt.truncatedTo(ChronoUnit.MILLIS).toString()); // DB 는 마이크로초
    b.put("mfcId", "MFC-1");
    b.put("deliveryMode", mode);
    if ("SCHEDULED".equals(mode)) {
      b.put(
          "arrivalWindow",
          Map.of(
              "date",
              LocalDate.now(SEOUL).plusDays(1).toString(),
              "start",
              "13:00",
              "end",
              "14:00"));
    }
    b.put("amount", 18100);
    b.put(
        "lines",
        List.of(
            Map.of("orderLineId", "L1", "sku", "E-00000001", "quantity", 2),
            Map.of("orderLineId", "L2", "sku", "E-00000003", "quantity", 1)));
    return b;
  }

  record Res(int status, JsonNode body) {}

  Res start(String key, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/internal/checkout-sagas")
            .headers(
                h -> {
                  h.setBearerAuth(SERVICE);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "order-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  JsonNode saga(String sagaId) {
    String b =
        http.get()
            .uri("/internal/sagas/{id}", sagaId)
            .headers(h -> h.setBearerAuth(SERVICE))
            .retrieve()
            .body(String.class);
    return mapper.readTree(b);
  }

  JsonNode await(String sagaId, Predicate<JsonNode> done) throws InterruptedException {
    JsonNode s = saga(sagaId);
    for (int i = 0; i < 100 && !done.test(s); i++) {
      Thread.sleep(100);
      s = saga(sagaId);
    }
    return s;
  }

  static JsonNode step(JsonNode saga, String name) {
    for (JsonNode s : saga.path("steps")) {
      if (name.equals(s.path("step").asString())) {
        return s;
      }
    }
    throw new AssertionError("단계 없음 " + name);
  }

  @Test
  void scheduledCheckoutReachesPaymentWithAllResourcesHeld() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    Instant expires = Instant.now().plus(10, ChronoUnit.MINUTES);
    participants(orderId, expires, "HELD", capacityOk(orderId));
    String key = UUID.randomUUID().toString();
    Map<String, Object> body =
        request(orderId, "SCHEDULED", Instant.now().plus(9, ChronoUnit.MINUTES));

    Res r = start(key, body);
    assertThat(r.status()).isEqualTo(202);
    String sagaId = r.body().path("resultId").asString();
    assertThat(start(key, body).body().path("resultId").asString()).isEqualTo(sagaId); // 같은 키 재전송

    JsonNode s = await(sagaId, x -> "WAITING_RESULT".equals(x.path("state").asString()));
    assertThat(s.path("state").asString()).isEqualTo("WAITING_RESULT");
    assertThat(s.path("currentStep").asString()).isEqualTo("AWAIT_PAYMENT_RESULT");
    assertThat(step(s, "RESERVE_INVENTORY").path("resultId").asString())
        .isEqualTo("RSV-" + orderId);
    assertThat(step(s, "HOLD_CAPACITY").path("state").asString()).isEqualTo("SUCCEEDED");
    assertThat(step(s, "HOLD_SLOT").path("resultId").asString()).isEqualTo("SH-" + orderId);
    assertThat(s.path("compensationLog")).hasSize(3);
    assertThat(s.path("payment").path("paymentAttemptId").asString()).isEqualTo("PAY-" + orderId);
    assertThat(s.path("payment").path("clientKey").asString()).isEqualTo("test_ck_docs");
    // 결제창 유효 = 가격 동결(9분)과 자원 만료(10분) 중 이른 것
    assertThat(Instant.parse(s.path("payment").path("expiresAt").asString()))
        .isBeforeOrEqualTo(Instant.parse(body.get("quoteExpiresAt").toString()));

    // 하위 명령 키는 sagaId:단계, 재고 예약은 행 그대로·TTL 10분, 작업 창은 도착 슬롯(KST 13시)
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/reservations"))
            .withHeader("Idempotency-Key", WireMock.equalTo(sagaId + ":RESERVE_INVENTORY"))
            .withRequestBody(matchingJsonPath("$.ttlMinutes", WireMock.equalTo("10")))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", WireMock.equalTo("2"))));
    String date = LocalDate.now(SEOUL).plusDays(1).toString();
    String windowStart =
        java.time.ZonedDateTime.parse(date + "T13:00:00+09:00[Asia/Seoul]").toInstant().toString();
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/capacity-holds"))
            .withRequestBody(matchingJsonPath("$.window", WireMock.containing(windowStart))));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/payments"))
            .withRequestBody(matchingJsonPath("$.customerRef", WireMock.equalTo("CU-1")))
            .withRequestBody(matchingJsonPath("$.amount", WireMock.equalTo("18100"))));

    // 진행 중 같은 초안 → 409
    assertThat(start(UUID.randomUUID().toString(), body).status()).isEqualTo(409);
  }

  @Test
  void capacityRejectionReleasesOnlyTheReservationAcquiredInThisAttempt() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    participants(
        orderId,
        Instant.now().plus(10, ChronoUnit.MINUTES),
        "RELEASED",
        json(422, Map.of("code", "CAPACITY_EXCEEDED", "detail", "작업 여력이 없습니다.", "status", 422)));
    Res r =
        start(
            UUID.randomUUID().toString(),
            request(orderId, "SINGLE", Instant.now().plus(9, ChronoUnit.MINUTES)));
    String sagaId = r.body().path("resultId").asString();

    JsonNode s = await(sagaId, x -> "COMPENSATED".equals(x.path("state").asString()));
    assertThat(s.path("state").asString()).isEqualTo("COMPENSATED");
    assertThat(s.path("lastError").path("code").asString()).isEqualTo("CAPACITY_EXCEEDED");
    assertThat(s.path("lastError").path("step").asString()).isEqualTo("HOLD_CAPACITY");
    assertThat(step(s, "HOLD_SLOT").path("state").asString()).isEqualTo("PENDING");
    assertThat(s.path("compensationLog")).hasSize(1);
    assertThat(s.path("compensationLog").path(0).path("releaseState").asString())
        .isEqualTo("RELEASED");
    assertThat(s.path("compensationLog").path(0).hasNonNull("releaseVerifiedAt")).isTrue();
    parts.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-" + orderId + "/releases"))
            .withHeader(
                "Idempotency-Key", WireMock.equalTo(sagaId + ":RELEASE:INVENTORY_RESERVATION"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("SAGA_COMPENSATION"))));
    parts.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/payments")));

    // 실패로 끝난 초안은 다시 결제 진입할 수 있다(진행 중 사가가 없다)
    participants(orderId, Instant.now().plus(10, ChronoUnit.MINUTES), "HELD", capacityOk(orderId));
    Res again =
        start(
            UUID.randomUUID().toString(),
            request(orderId, "SINGLE", Instant.now().plus(9, ChronoUnit.MINUTES)));
    assertThat(again.status()).isEqualTo(202);
    assertThat(again.body().path("resultId").asString()).isNotEqualTo(sagaId);
  }

  @Test
  void unpaidCheckoutIsReleasedAfterResourcesExpire() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    // 정본 예약 만료가 이미 지났다(만료 처리 경로를 바로 시험)
    participants(orderId, Instant.now().minusSeconds(5), "EXPIRED", capacityOk(orderId));
    String sagaId =
        start(
                UUID.randomUUID().toString(),
                request(orderId, "BUNDLED", Instant.now().plus(9, ChronoUnit.MINUTES)))
            .body()
            .path("resultId")
            .asString();
    await(sagaId, x -> "WAITING_RESULT".equals(x.path("state").asString()));

    sagas.sweep(); // 결제 시도 PENDING(미승인) → 이번 시도분 해제
    JsonNode s = await(sagaId, x -> "COMPENSATED".equals(x.path("state").asString()));
    assertThat(s.path("state").asString()).isEqualTo("COMPENSATED");
    assertThat(s.path("lastError").path("code").asString()).isEqualTo("RESOURCE_EXPIRED");
    assertThat(step(s, "AWAIT_PAYMENT_RESULT").path("state").asString()).isEqualTo("TIMED_OUT");
    assertThat(step(s, "HOLD_SLOT").path("state").asString()).isEqualTo("SKIPPED"); // 알뜰은 슬롯 없음
    assertThat(s.path("compensationLog")).hasSize(2);
  }

  @Test
  void expiredQuoteIsRejectedBeforeAnySaga() {
    String orderId = "ORD-" + UUID.randomUUID();
    Res r =
        start(
            UUID.randomUUID().toString(),
            request(orderId, "SINGLE", Instant.now().minusSeconds(1)));
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.body().path("code").asString()).isEqualTo("RE_QUOTE_REQUIRED");
  }

  @Test
  void customerTokenCannotStartSaga() {
    var e =
        http.post()
            .uri("/internal/checkout-sagas")
            .headers(
                h -> {
                  h.setBearerAuth(TestJwt.customer("CU-1"));
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(request("ORD-X", "SINGLE", Instant.now().plusSeconds(60)))
            .retrieve()
            .toEntity(String.class);
    assertThat(e.getStatusCode().value()).isEqualTo(403);
  }
}
