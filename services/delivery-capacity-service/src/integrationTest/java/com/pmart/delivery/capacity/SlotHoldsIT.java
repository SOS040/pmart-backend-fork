package com.pmart.delivery.capacity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.delivery.capacity.application.HoldExpirySweeper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 슬롯 점유(HoldSlot): 점유·확정·해제·만료, 조회 잔여 반영, 정원 소진, 마지막 1자리 동시 점유, 같은 키 재전송, 선택 불가 시간대. 정원 2(시험값).
 * center-master·auth 는 WireMock. 테스트마다 내일의 다른 시간대를 써서 서로 섞이지 않게 한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${pmart.capacity.center-master-base-url}/api/v1/internal/service-tokens",
      "pmart.capacity.slot-capacity=2",
      "pmart.capacity.hold-sweep-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "center",
        baseUrlProperties = "pmart.capacity.center-master-base-url"))
class SlotHoldsIT extends PostgresKafkaContainerSupport {

  static final String SERVICE_TOKEN = TestJwt.service("delivery-capacity-service");
  static final String ORCH = TestJwt.service("order-orchestration-service");
  static final String TOMORROW = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString();

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired DataSource dataSource;
  @Autowired HoldExpirySweeper sweeper;

  @InjectWireMock("center")
  WireMockServer center;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    center.resetAll();
    center.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                json(
                    Map.of(
                        "access_token",
                        SERVICE_TOKEN,
                        "token_type",
                        "Bearer",
                        "expires_in",
                        300))));
    center.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/MFC-1/calendar"))
            .willReturn(
                json(
                    Map.of(
                        "centerId",
                        "MFC-1",
                        "open",
                        true,
                        "calendarVersion",
                        3,
                        "ranges",
                        List.of(Map.of("start", "06:00", "end", "22:00"))))));
    center.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/MFC-NOTABLE/calendar"))
            .willReturn(aResponse().withStatus(422)));
    center.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/MFC-DOWN/calendar"))
            .willReturn(aResponse().withStatus(503)));
  }

  com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(Object body) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  record Res(int status, JsonNode body) {}

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return res(e);
  }

  Res post(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "order-orchestration-service");
                  h.set("Content-Type", "application/json");
                })
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .toEntity(String.class);
    return res(e);
  }

  Res res(org.springframework.http.ResponseEntity<String> e) {
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> holdBody(String orderId, String mfcId, String date, String start) {
    int h = Integer.parseInt(start.substring(0, 2));
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", orderId);
    b.put("mfcId", mfcId);
    b.put("window", Map.of("date", date, "start", start, "end", String.format("%02d:00", h + 1)));
    return b;
  }

  Res hold(String orderId, String start) {
    return post(ORCH, "/internal/slot-holds", key(), holdBody(orderId, "MFC-1", TOMORROW, start));
  }

  JsonNode slot(String start) {
    Res r = get(TestJwt.customer("CU-1"), "/slots?mfcId=MFC-1&date=" + TOMORROW);
    assertThat(r.status()).isEqualTo(200);
    for (JsonNode s : r.body().path("slots")) {
      if (s.path("window").path("start").asString().equals(start)) {
        return s;
      }
    }
    throw new AssertionError("slot " + start + " 없음");
  }

  /** 이 서비스 DB 직접 조작(TTL 경과 흉내·저장 상태 확인). 조회면 첫 열, 갱신이면 null. */
  String sql(String statement, String holdId) throws java.sql.SQLException {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(statement)) {
      ps.setString(1, holdId);
      if (!ps.execute()) {
        return null;
      }
      try (var rs = ps.getResultSet()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  static String key() {
    return UUID.randomUUID().toString();
  }

  static String order() {
    return "ORD-" + UUID.randomUUID();
  }

  @Test
  void holdConfirmReleaseAndListingReflectsHolds() {
    String orderId = order();
    String key = key();
    Map<String, Object> body = holdBody(orderId, "MFC-1", TOMORROW, "10:00");
    body.put("ttlSeconds", 300);
    Res created = post(ORCH, "/internal/slot-holds", key, body);
    assertThat(created.status()).isEqualTo(201);
    String holdId = created.body().path("holdId").asString();
    assertThat(created.body().path("state").asString()).isEqualTo("HELD");
    assertThat(created.body().path("slotId").asString()).isEqualTo("MFC-1:" + TOMORROW + ":10:00");
    assertThat(created.body().path("version").asInt()).isEqualTo(1);

    // 같은 키·같은 본문 → 같은 점유(201), 같은 키·다른 본문 → 409 IDEMPOTENCY_CONFLICT
    Res replay = post(ORCH, "/internal/slot-holds", key, body);
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.body().path("holdId").asString()).isEqualTo(holdId);
    Res conflict =
        post(ORCH, "/internal/slot-holds", key, holdBody(orderId, "MFC-1", TOMORROW, "11:00"));
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body().path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
    // 같은 주문의 다른 키 점유 → 409 HOLD_ALREADY_ACTIVE
    Res second = hold(orderId, "11:00");
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.body().path("code").asString()).isEqualTo("HOLD_ALREADY_ACTIVE");

    // 조회 잔여에 반영
    JsonNode s = slot("10:00");
    assertThat(s.path("heldCount").asInt()).isEqualTo(1);
    assertThat(s.path("remaining").asInt()).isEqualTo(1);
    assertThat(s.path("availability").asString()).isEqualTo("AVAILABLE");

    // 확정 (다른 주문 번호는 거절)
    assertThat(
            post(
                    ORCH,
                    "/internal/slot-holds/" + holdId + "/confirmations",
                    key(),
                    Map.of("orderId", "ORD-OTHER"))
                .status())
        .isEqualTo(409);
    String confirmKey = key();
    Res confirmed =
        post(
            ORCH,
            "/internal/slot-holds/" + holdId + "/confirmations",
            confirmKey,
            Map.of("orderId", orderId));
    assertThat(confirmed.status()).isEqualTo(200);
    assertThat(confirmed.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(confirmed.body().path("version").asInt()).isEqualTo(2);
    // 다른 키로 다시 확정 → 멱등 성공(버전 그대로)
    Res again =
        post(
            ORCH,
            "/internal/slot-holds/" + holdId + "/confirmations",
            key(),
            Map.of("orderId", orderId));
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("version").asInt()).isEqualTo(2);
    Res confirmResult =
        get(ORCH, "/internal/slot-holds/" + holdId + "/confirmations/" + confirmKey);
    assertThat(confirmResult.status()).isEqualTo(200);
    assertThat(confirmResult.body().path("resultingState").asString()).isEqualTo("CONFIRMED");
    assertThat(get(ORCH, "/internal/slot-holds/" + holdId + "/confirmations/nope").status())
        .isEqualTo(404);

    s = slot("10:00");
    assertThat(s.path("heldCount").asInt()).isZero();
    assertThat(s.path("confirmedCount").asInt()).isEqualTo(1);
    assertThat(s.path("remaining").asInt()).isEqualTo(1);

    // 운영자 해제는 승인 근거 필요
    Res noApproval =
        post(
            ORCH,
            "/internal/slot-holds/" + holdId + "/releases",
            key(),
            Map.of("reason", "OPERATOR"));
    assertThat(noApproval.status()).isEqualTo(422);
    assertThat(noApproval.body().path("code").asString()).isEqualTo("APPROVAL_REQUIRED");
    // 낡은 기대 버전 → 409 VERSION_CONFLICT
    var stale =
        http.post()
            .uri("/internal/slot-holds/" + holdId + "/releases")
            .headers(
                h -> {
                  h.setBearerAuth(ORCH);
                  h.set("Idempotency-Key", key());
                  h.set("X-Source-System", "order-orchestration-service");
                  h.set("X-Expected-Version", "1");
                  h.set("Content-Type", "application/json");
                })
            .body("{\"reason\":\"ORDER_CANCELLED\"}")
            .retrieve()
            .toEntity(String.class);
    assertThat(stale.getStatusCode().value()).isEqualTo(409);

    String releaseKey = key();
    Res released =
        post(
            ORCH,
            "/internal/slot-holds/" + holdId + "/releases",
            releaseKey,
            Map.of("reason", "ORDER_CANCELLED"));
    assertThat(released.status()).isEqualTo(200);
    assertThat(released.body().path("version").asInt()).isEqualTo(3);
    // 이미 RELEASED → 멱등 성공
    assertThat(
            post(
                    ORCH,
                    "/internal/slot-holds/" + holdId + "/releases",
                    key(),
                    Map.of("reason", "ORDER_CANCELLED"))
                .status())
        .isEqualTo(200);
    assertThat(
            get(ORCH, "/internal/slot-holds/" + holdId + "/releases/" + releaseKey)
                .body()
                .path("resultingState")
                .asString())
        .isEqualTo("RELEASED");
    Res fetched = get(ORCH, "/internal/slot-holds/" + holdId);
    assertThat(fetched.body().path("state").asString()).isEqualTo("RELEASED");
    assertThat(fetched.body().path("releaseReason").asString()).isEqualTo("ORDER_CANCELLED");
    // RELEASED 는 확정 불가
    assertThat(
            post(
                    ORCH,
                    "/internal/slot-holds/" + holdId + "/confirmations",
                    key(),
                    Map.of("orderId", orderId))
                .body()
                .path("code")
                .asString())
        .isEqualTo("INVALID_STATE");
    assertThat(slot("10:00").path("remaining").asInt()).isEqualTo(2);

    // 해제 후 같은 주문은 다시 점유할 수 있다
    assertThat(hold(orderId, "11:00").status()).isEqualTo(201);
    assertThat(get(ORCH, "/internal/slot-holds/SH-none").status()).isEqualTo(404);
  }

  @Test
  void fullSlotRejectsWithCapacityExceeded() {
    assertThat(hold(order(), "12:00").status()).isEqualTo(201);
    assertThat(hold(order(), "12:00").status()).isEqualTo(201);
    JsonNode s = slot("12:00");
    assertThat(s.path("remaining").asInt()).isZero();
    assertThat(s.path("availability").asString()).isEqualTo("FULL");
    Res full = hold(order(), "12:00");
    assertThat(full.status()).isEqualTo(422);
    assertThat(full.body().path("code").asString()).isEqualTo("CAPACITY_EXCEEDED");
  }

  @Test
  void concurrentHoldsForLastSeatOnlyOneWins() throws Exception {
    assertThat(hold(order(), "13:00").status()).isEqualTo(201); // 남은 자리 1
    int n = 4;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Res>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Callable<Res> c =
          () -> {
            start.await();
            return hold(order(), "13:00");
          };
      futures.add(pool.submit(c));
    }
    start.countDown();
    List<Res> results = new ArrayList<>();
    for (Future<Res> f : futures) {
      results.add(f.get());
    }
    pool.shutdown();
    assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
    assertThat(results.stream().filter(r -> r.status() == 422))
        .hasSize(n - 1)
        .allMatch(r -> r.body().path("code").asString().equals("CAPACITY_EXCEEDED"));
    assertThat(slot("13:00").path("heldCount").asInt()).isEqualTo(2);
  }

  @Test
  void notSelectableAndConfigRequired() {
    String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    String yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString();
    String later = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(2).toString();
    // 07시 도착(운영 06시부터여도 08시 전 없음) · 정시 아님 · 운영 밖 · 어제 · 모레
    for (Map<String, Object> b :
        List.of(
            holdBody(order(), "MFC-1", TOMORROW, "07:00"),
            Map.of(
                "orderId",
                order(),
                "mfcId",
                "MFC-1",
                "window",
                Map.of("date", TOMORROW, "start", "08:30", "end", "09:30")),
            holdBody(order(), "MFC-1", TOMORROW, "22:00"),
            holdBody(order(), "MFC-1", yesterday, "14:00"),
            holdBody(order(), "MFC-1", later, "14:00"),
            // 오늘 00시 시간대는 이미 마감
            holdBody(order(), "MFC-1", today, "00:00"))) {
      Res r = post(ORCH, "/internal/slot-holds", key(), b);
      assertThat(r.status()).as(b.toString()).isEqualTo(422);
      assertThat(r.body().path("code").asString())
          .as(b.toString())
          .isEqualTo("SLOT_NOT_SELECTABLE");
    }
    // 형식 오류 400, TTL 범위 밖 400
    assertThat(
            post(
                    ORCH,
                    "/internal/slot-holds",
                    key(),
                    Map.of(
                        "orderId",
                        order(),
                        "mfcId",
                        "MFC-1",
                        "window",
                        Map.of("date", TOMORROW, "start", "9시", "end", "10시")))
                .status())
        .isEqualTo(400);
    Map<String, Object> badTtl = holdBody(order(), "MFC-1", TOMORROW, "14:00");
    badTtl.put("ttlSeconds", 5);
    assertThat(post(ORCH, "/internal/slot-holds", key(), badTtl).status()).isEqualTo(400);
    // 시간표 미승인·center-master 장애 → CONFIG_REQUIRED (계약: 조회 실패도 신규 결제 보류)
    for (String mfc : List.of("MFC-NOTABLE", "MFC-DOWN")) {
      Res r = post(ORCH, "/internal/slot-holds", key(), holdBody(order(), mfc, TOMORROW, "14:00"));
      assertThat(r.status()).as(mfc).isEqualTo(422);
      assertThat(r.body().path("code").asString()).as(mfc).isEqualTo("CONFIG_REQUIRED");
    }
    // S4 교체는 아직 미지원
    Map<String, Object> s4 = holdBody(order(), "MFC-1", TOMORROW, "14:00");
    s4.put("newSlotFor", "SH-OLD");
    Res r = post(ORCH, "/internal/slot-holds", key(), s4);
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.body().path("code").asString()).isEqualTo("UNSUPPORTED_ACTION");
    // /internal 은 서비스 토큰만
    assertThat(
            post(
                    TestJwt.customer("CU-1"),
                    "/internal/slot-holds",
                    key(),
                    holdBody(order(), "MFC-1", TOMORROW, "14:00"))
                .status())
        .isEqualTo(403);
  }

  @Test
  void expiredHoldIsSweptAndSeatReturned() throws Exception {
    Map<String, Object> body = holdBody(order(), "MFC-1", TOMORROW, "15:00");
    body.put("ttlSeconds", 60);
    Res created = post(ORCH, "/internal/slot-holds", key(), body);
    assertThat(created.status()).isEqualTo(201);
    String holdId = created.body().path("holdId").asString();
    assertThat(slot("15:00").path("remaining").asInt()).isEqualTo(1);

    // TTL 경과를 흉내 낸다
    sql("UPDATE slot_hold SET expires_at = now() - interval '1 second' WHERE hold_id = ?", holdId);
    // 정리 전이라도 정원에서 빠지고 EXPIRED 로 보이며, 확정은 거절
    assertThat(slot("15:00").path("remaining").asInt()).isEqualTo(2);
    assertThat(get(ORCH, "/internal/slot-holds/" + holdId).body().path("state").asString())
        .isEqualTo("EXPIRED");
    assertThat(
            post(
                    ORCH,
                    "/internal/slot-holds/" + holdId + "/confirmations",
                    key(),
                    Map.of("orderId", body.get("orderId")))
                .status())
        .isEqualTo(409);

    assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);
    assertThat(sql("SELECT state FROM slot_hold WHERE hold_id = ?", holdId)).isEqualTo("EXPIRED");
    // 만료된 점유 해제는 멱등 성공
    Res released =
        post(
            ORCH,
            "/internal/slot-holds/" + holdId + "/releases",
            key(),
            Map.of("reason", "SAGA_COMPENSATION"));
    assertThat(released.status()).isEqualTo(200);
    assertThat(released.body().path("status").asString()).isEqualTo("APPLIED");
  }
}
