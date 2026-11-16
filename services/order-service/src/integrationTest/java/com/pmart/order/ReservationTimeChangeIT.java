package com.pmart.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 예약배달 시간 변경(S4, 0.10.0): 고객 요청 검증 → PENDING·orchestration 사가 시작(202) → 사가 결과 CONFIRMED 면 주문 도착
 * 시간대·버전· ReservationTimeChanged, REJECTED 면 그대로. 준비 시작 뒤·즉시 배송·같은 시간대·진행 중 변경·남의 주문은 거절.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=rtc-test",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(name = "orch", baseUrlProperties = "pmart.order.orchestration-base-url"))
class ReservationTimeChangeIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("orch")
  WireMockServer orch;

  static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    orch.resetAll();
    orch.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/time-change-sagas"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"ACCEPTED\",\"requestId\":\"k\",\"resultId\":\"SAGA-S4-1\"}")));
  }

  String order(String account, String status, String mode) {
    String orderId = "ORD-RTC-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO customer_order (order_id, account_id, draft_id, mfc_id, status, version,"
            + " delivery_mode, arrival_date, arrival_start, arrival_end, out_of_stock_preference,"
            + " shopping_address_id, quote_version, price_version, item_amount, delivery_tip_base,"
            + " delivery_tip, delivery_tip_reason, bag_fee, total_amount, first_purchase_applied,"
            + " weight_g, planned_shipments, payment_attempt_id, pg_transaction_id, approved_amount,"
            + " reservation_id, confirmed_at) VALUES (?,?,?,'MFC-1',?,1,?,?,'18:00','19:00',"
            + " 'CANCEL_ITEM','ADDR-1',1,1,20000,4000,4000,'BASE',100,24100,false,1000,1,'PAY-1',"
            + " 'PG-1',24100,'RSV-1',?)",
        orderId,
        account,
        "DRF-" + orderId,
        status,
        mode,
        "SCHEDULED".equals(mode) ? java.sql.Date.valueOf(TODAY) : null,
        Timestamp.from(Instant.now()));
    return orderId;
  }

  Res call(String method, String path, String token, Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "it");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange(
        (req, res) -> {
          byte[] b = res.getBody().readAllBytes();
          return new Res(
              res.getStatusCode().value(),
              b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
        });
  }

  static Map<String, Object> window(LocalDate date, String start, String end) {
    return Map.of("newArrivalWindow", Map.of("date", date.toString(), "start", start, "end", end));
  }

  String arrivalStart(String orderId) {
    return jdbc.queryForObject(
        "SELECT arrival_date || ' ' || arrival_start FROM customer_order WHERE order_id = ?",
        String.class,
        orderId);
  }

  @Test
  void requestStartsSagaAndConfirmedResultChangesOrder() {
    String me = "ACC-RTC-" + UUID.randomUUID();
    String o = order(me, "CONFIRMED", "SCHEDULED");
    String token = TestJwt.customer(me);
    String path = "/orders/" + o + "/reservation-time-changes";
    LocalDate tomorrow = TODAY.plusDays(1);
    Res r = call("POST", path, token, window(tomorrow, "20:00", "21:00"));
    assertThat(r.status()).isEqualTo(202);
    String req = r.body().path("resultId").asString();
    orch.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/time-change-sagas"))
            .withRequestBody(matchingJsonPath("$.requestId", WireMock.equalTo(req)))
            .withRequestBody(
                matchingJsonPath("$.currentArrivalWindow.start", WireMock.equalTo("18:00")))
            .withRequestBody(
                matchingJsonPath(
                    "$.newArrivalWindow.date", WireMock.equalTo(tomorrow.toString()))));
    assertThat(call("GET", path + "/" + req, token, null).body().path("state").asString())
        .isEqualTo("PENDING");
    // 진행 중 변경·남의 주문
    assertThat(
            call("POST", path, token, window(tomorrow, "21:00", "22:00"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("TIME_CHANGE_IN_PROGRESS");
    assertThat(
            call("POST", path, TestJwt.customer("ACC-OTHER"), window(tomorrow, "21:00", "22:00"))
                .status())
        .isEqualTo(404);
    // 사가 결과 CONFIRMED → 주문 시간대·버전·사건
    String svc = TestJwt.service("order-orchestration-service");
    Res done =
        call(
            "POST",
            "/internal/orders/" + o + "/reservation-time-changes/" + req + "/results",
            svc,
            Map.of("result", "CONFIRMED", "sagaId", "SAGA-S4-1"));
    assertThat(done.status()).isEqualTo(200);
    assertThat(arrivalStart(o)).isEqualTo(tomorrow + " 20:00");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM customer_order WHERE order_id = ?", Integer.class, o))
        .isEqualTo(2);
    JsonNode ev =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type ="
                    + " 'ReservationTimeChanged'",
                String.class,
                o));
    assertThat(ev.path("payload").path("arrivalWindow").path("start").asString())
        .isEqualTo("20:00");
    assertThat(ev.path("payload").path("previousArrivalWindow").path("start").asString())
        .isEqualTo("18:00");
    JsonNode got = call("GET", path + "/" + req, token, null).body();
    assertThat(got.path("state").asString()).isEqualTo("CONFIRMED");
    // 같은 결과 다시 → 그대로(버전 안 오름)
    call(
        "POST",
        "/internal/orders/" + o + "/reservation-time-changes/" + req + "/results",
        svc,
        Map.of("result", "CONFIRMED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM customer_order WHERE order_id = ?", Integer.class, o))
        .isEqualTo(2);

    // 두 번째 요청 → REJECTED 면 그대로
    Res second = call("POST", path, token, window(tomorrow, "21:00", "22:00"));
    String req2 = second.body().path("resultId").asString();
    call(
        "POST",
        "/internal/orders/" + o + "/reservation-time-changes/" + req2 + "/results",
        svc,
        Map.of("result", "REJECTED", "reason", "PREPARATION_STARTED"));
    assertThat(arrivalStart(o)).isEqualTo(tomorrow + " 20:00");
    JsonNode rej = call("GET", path + "/" + req2, token, null).body();
    assertThat(rej.path("state").asString()).isEqualTo("REJECTED");
    assertThat(rej.path("reason").asString()).isEqualTo("PREPARATION_STARTED");
  }

  @Test
  void rejectsAfterPreparationImmediateAndSameSlot() {
    String me = "ACC-RTC-" + UUID.randomUUID();
    String token = TestJwt.customer(me);
    String preparing = order(me, "PREPARING", "SCHEDULED");
    assertThat(
            call(
                    "POST",
                    "/orders/" + preparing + "/reservation-time-changes",
                    token,
                    window(TODAY.plusDays(1), "20:00", "21:00"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("INVALID_STATE");
    String now = order(me, "CONFIRMED", "SINGLE");
    assertThat(
            call(
                    "POST",
                    "/orders/" + now + "/reservation-time-changes",
                    token,
                    window(TODAY.plusDays(1), "20:00", "21:00"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_SCHEDULED");
    String same = order(me, "CONFIRMED", "SCHEDULED");
    assertThat(
            call(
                    "POST",
                    "/orders/" + same + "/reservation-time-changes",
                    token,
                    window(TODAY, "18:00", "19:00"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("SAME_SLOT");
    orch.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/time-change-sagas")));
  }
}
