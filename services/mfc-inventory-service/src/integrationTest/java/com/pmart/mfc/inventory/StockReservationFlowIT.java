package com.pmart.mfc.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 재고 원장·예약(S1 a): 수령 전기(receiptId 멱등) → 가용 → 예약 all-or-nothing·동시 경합(TC-09) → 같은 키 재생 → 확정·해제 → 만료
 * 작업(InventoryHoldExpired Outbox). 실제 PostgreSQL·Kafka, 시험 서비스 토큰.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.inventory.hold-expiry-interval=PT1S"
    })
@Import(TestJwtConfiguration.class)
class StockReservationFlowIT extends PostgresKafkaContainerSupport {

  static final String INBOUND = TestJwt.service("mfc-inbound-service");
  static final String ORCH = TestJwt.service("order-orchestration-service");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  RestClient http;
  String mfc;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    mfc = "MFC-IT-" + UUID.randomUUID().toString().substring(0, 8);
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String source, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", source);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  JsonNode read(String body) {
    return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
  }

  static String newSku() {
    return "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
  }

  /** 센터 취급 ON(이 서비스 표 — 시험 준비). */
  void handlingOn(String sku) {
    jdbc.update(
        "INSERT INTO center_sku_handling (center_id, sku, handling_enabled, changed_by, updated_at,"
            + " row_version) VALUES (?,?,true,'it',now(),1)",
        mfc,
        sku);
  }

  Map<String, Object> receipt(String receiptId, String sku, int normal, int quarantined) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", sku);
    line.put("normalQty", normal);
    line.put("quarantinedQty", quarantined);
    line.put("pendingQty", 0);
    return Map.of(
        "receiptId",
        receiptId,
        "trfId",
        "INITIAL-AP-1",
        "departureId",
        "INITIAL",
        "mfcId",
        mfc,
        "lines",
        List.of(line));
  }

  Res postReceipt(String key, Map<String, Object> body) {
    return post(INBOUND, "mfc-inbound-service", "/internal/receipts", key, body);
  }

  Map<String, Object> reserveBody(String orderId, Map<String, Integer> lines) {
    List<Map<String, Object>> ls = new ArrayList<>();
    lines.forEach((s, q) -> ls.add(Map.of("sku", s, "qty", q)));
    return Map.of("orderId", orderId, "mfcId", mfc, "lines", ls);
  }

  Res reserve(String key, Map<String, Object> body) {
    return post(ORCH, "order-orchestration-service", "/internal/reservations", key, body);
  }

  JsonNode availability(String sku) {
    Res r = get(ORCH, "/internal/availability?mfcId=" + mfc + "&sku=" + sku);
    assertThat(r.status()).isEqualTo(200);
    return r.body().path("items").path(0);
  }

  @Test
  void receiptIsPostedOnceAndFeedsAvailability() {
    String sku = newSku();
    String receiptId = "RCV-" + UUID.randomUUID();

    // 정상 5, 격리 1 → H6 Q1 E5
    Res first = postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, 5, 1));
    assertThat(first.status()).isEqualTo(201);
    assertThat(first.body().path("status").asString()).isEqualTo("APPLIED");
    String commitId = first.body().path("resultId").asString();
    assertThat(commitId).startsWith("CMT-");
    assertThat(first.body().path("appliedQty").asInt()).isEqualTo(6);

    // 같은 receiptId·같은 내용 → 다른 키여도 같은 commitId, 이중 계상 없음
    Res again = postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, 5, 1));
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.body().path("resultId").asString()).isEqualTo(commitId);
    // 같은 키·같은 본문 → 저장 결과 재생
    String key = UUID.randomUUID().toString();
    Res k1 = postReceipt(key, receipt("RCV-" + UUID.randomUUID(), sku, 1, 0));
    // 다른 receiptId 를 같은 키로 → 409 IDEMPOTENCY_CONFLICT
    assertThat(postReceipt(key, receipt("RCV-" + UUID.randomUUID(), sku, 1, 0)).status())
        .isEqualTo(409);
    assertThat(k1.status()).isEqualTo(201);
    // 같은 receiptId·다른 내용 → 409 RECEIPT_ALREADY_POSTED
    Res conflict = postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, 4, 1));
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body().path("code").asString()).isEqualTo("RECEIPT_ALREADY_POSTED");

    JsonNode a = availability(sku);
    assertThat(a.path("onHand").asInt()).isEqualTo(7);
    assertThat(a.path("quarantined").asInt()).isEqualTo(1);
    assertThat(a.path("sellable").asInt()).isEqualTo(6);
    assertThat(a.path("reserved").asInt()).isZero();
    assertThat(a.path("available").asInt()).isEqualTo(6);
    assertThat(a.path("handlingEnabled").asBoolean()).isFalse();
    // 원장 행이 없는 SKU 는 0
    assertThat(availability(newSku()).path("available").asInt()).isZero();

    // 전기 조회
    Res commit = get(INBOUND, "/internal/commits/" + commitId);
    assertThat(commit.status()).isEqualTo(200);
    assertThat(commit.body().path("commitType").asString()).isEqualTo("TIN");
    assertThat(commit.body().path("sourceRef").asString()).isEqualTo(receiptId);
    assertThat(commit.body().path("entries")).hasSize(3); // H+6, E+5, Q+1

    // 운영자·고객 토큰은 /internal 403
    assertThat(
            post(
                    TestJwt.operator("op", List.of(), List.of(mfc)),
                    "backoffice-web",
                    "/internal/receipts",
                    UUID.randomUUID().toString(),
                    receipt("RCV-" + UUID.randomUUID(), sku, 1, 0))
                .status())
        .isEqualTo(403);
  }

  @Test
  void reserveIsAllOrNothingAndReplayable() {
    String a = newSku();
    String b = newSku();
    handlingOn(a);
    postReceipt(UUID.randomUUID().toString(), receipt("RCV-" + UUID.randomUUID(), a, 5, 0));

    // 취급 OFF SKU 포함 → 422 SKU_NOT_HANDLED
    Res off = reserve(UUID.randomUUID().toString(), reserveBody("ORD-1", Map.of(a, 1, b, 1)));
    assertThat(off.status()).isEqualTo(422);
    assertThat(off.body().path("code").asString()).isEqualTo("SKU_NOT_HANDLED");
    assertThat(off.body().path("details").path(0).path("field").asString()).isEqualTo(b);

    // 취급 ON 이지만 재고 0 인 b 포함 → 전체 거절, a 도 확보 0
    handlingOn(b);
    Res shortB = reserve(UUID.randomUUID().toString(), reserveBody("ORD-2", Map.of(a, 1, b, 1)));
    assertThat(shortB.status()).isEqualTo(422);
    assertThat(shortB.body().path("code").asString()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(shortB.body().path("details").path(0).path("field").asString()).isEqualTo(b);
    assertThat(shortB.body().path("details").path(0).path("reason").asString())
        .isEqualTo("requestedQty=1;availableQty=0");
    assertThat(availability(a).path("reserved").asInt()).isZero();

    // TC-09 별도 시험: 같은 SKU 두 행 3+3 = 6 > A5 → 전체 거절
    List<Map<String, Object>> dup = List.of(Map.of("sku", a, "qty", 3), Map.of("sku", a, "qty", 3));
    Res dupRes =
        reserve(
            UUID.randomUUID().toString(), Map.of("orderId", "ORD-3", "mfcId", mfc, "lines", dup));
    assertThat(dupRes.status()).isEqualTo(422);
    assertThat(dupRes.body().path("details").path(0).path("reason").asString())
        .isEqualTo("requestedQty=6;availableQty=5");

    // 성공 → R4/A1, 같은 키·같은 본문 재전송 → 같은 결과, R 변화 0
    String key = UUID.randomUUID().toString();
    Res ok = reserve(key, reserveBody("ORD-4", Map.of(a, 4)));
    assertThat(ok.status()).isEqualTo(201);
    String reservationId = ok.body().path("resultId").asString();
    assertThat(reservationId).startsWith("RSV-");
    Res replay = reserve(key, reserveBody("ORD-4", Map.of(a, 4)));
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(ok.body());
    assertThat(availability(a).path("reserved").asInt()).isEqualTo(4);
    assertThat(availability(a).path("available").asInt()).isEqualTo(1);
    // 같은 키·다른 본문 → 409
    assertThat(reserve(key, reserveBody("ORD-4", Map.of(a, 1))).status()).isEqualTo(409);

    // 조회
    Res rv = get(ORCH, "/internal/reservations/" + reservationId);
    assertThat(rv.status()).isEqualTo(200);
    assertThat(rv.body().path("state").asString()).isEqualTo("HELD");
    assertThat(rv.body().path("orderId").asString()).isEqualTo("ORD-4");
    assertThat(rv.body().path("parts").path(0).path("qty").asInt()).isEqualTo(4);
    assertThat(rv.body().path("expiresAt").isNull()).isFalse();
    assertThat(get(ORCH, "/internal/reservations/RSV-none").status()).isEqualTo(404);

    // 원결과 조회: 예약 요청 → APPLIED + commitId, 거절된 요청 → 404
    Res req = get(ORCH, "/internal/requests/" + key);
    assertThat(req.status()).isEqualTo(200);
    assertThat(req.body().path("applyStatus").asString()).isEqualTo("APPLIED");
    assertThat(req.body().path("commitId").asString()).startsWith("CMT-");
    assertThat(req.body().path("appliedQty").asInt()).isEqualTo(4);
    assertThat(get(ORCH, "/internal/requests/" + UUID.randomUUID()).status()).isEqualTo(404);

    // 확정: 다른 주문 → 409, 낡은 버전 → 409, 정상 → CONFIRMED(만료 시각 지움), 재확정 → 변화 없는 성공
    String confirmPath = "/internal/reservations/" + reservationId + "/confirmations";
    assertThat(
            post(
                    ORCH,
                    "order-orchestration-service",
                    confirmPath,
                    UUID.randomUUID().toString(),
                    Map.of("orderId", "ORD-X"))
                .status())
        .isEqualTo(409);
    Res confirmed =
        post(
            ORCH,
            "order-orchestration-service",
            confirmPath,
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-4", "paymentAttemptId", "PAY-1"));
    assertThat(confirmed.status()).isEqualTo(200);
    assertThat(confirmed.body().path("version").asInt()).isEqualTo(2);
    assertThat(confirmed.body().path("appliedQty").asInt()).isEqualTo(4);
    Res reconfirm =
        post(
            ORCH,
            "order-orchestration-service",
            confirmPath,
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-4"));
    assertThat(reconfirm.status()).isEqualTo(200);
    assertThat(reconfirm.body().path("appliedQty").asInt()).isZero();
    JsonNode afterConfirm = get(ORCH, "/internal/reservations/" + reservationId).body();
    assertThat(afterConfirm.path("state").asString()).isEqualTo("CONFIRMED");
    assertThat(afterConfirm.path("expiresAt").isNull()).isTrue();
    assertThat(availability(a).path("reserved").asInt()).isEqualTo(4);

    // 부분 해제 → 422, 전량 해제 → A 복구, 재해제 → 변화 없는 성공, 해제 후 확정 → 409
    String releasePath = "/internal/reservations/" + reservationId + "/releases";
    Res partial =
        post(
            ORCH,
            "order-orchestration-service",
            releasePath,
            UUID.randomUUID().toString(),
            Map.of("reason", "CANCEL_BEFORE_PICK", "lines", List.of(Map.of("sku", a, "qty", 1))));
    assertThat(partial.status()).isEqualTo(422);
    assertThat(partial.body().path("code").asString()).isEqualTo("PARTIAL_RELEASE_NOT_SUPPORTED");
    Res released =
        post(
            ORCH,
            "order-orchestration-service",
            releasePath,
            UUID.randomUUID().toString(),
            Map.of("reason", "CANCEL_BEFORE_PICK"));
    assertThat(released.status()).isEqualTo(200);
    assertThat(released.body().path("appliedQty").asInt()).isEqualTo(4);
    assertThat(availability(a).path("reserved").asInt()).isZero();
    assertThat(availability(a).path("available").asInt()).isEqualTo(5);
    Res rerelease =
        post(
            ORCH,
            "order-orchestration-service",
            releasePath,
            UUID.randomUUID().toString(),
            Map.of("reason", "SAGA_COMPENSATION"));
    assertThat(rerelease.status()).isEqualTo(200);
    assertThat(rerelease.body().path("appliedQty").asInt()).isZero();
    assertThat(availability(a).path("available").asInt()).isEqualTo(5);
    Res confirmReleased =
        post(
            ORCH,
            "order-orchestration-service",
            confirmPath,
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-4", "paymentAttemptId", "PAY-2"));
    assertThat(confirmReleased.status()).isEqualTo(409);
    assertThat(confirmReleased.body().path("code").asString()).isEqualTo("INVALID_STATE");
  }

  @Test
  void concurrentReservationsOnLastUnitsOnlyOneSucceeds() throws Exception {
    // TC-09: H5/E5/R0/A5 에 주문 A4·B4 동시 → 하나만 성공(R4/A1)
    String sku = newSku();
    handlingOn(sku);
    postReceipt(UUID.randomUUID().toString(), receipt("RCV-" + UUID.randomUUID(), sku, 5, 0));
    int rounds = 2;
    ExecutorService pool = Executors.newFixedThreadPool(rounds);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Res>> futures = new ArrayList<>();
      for (int i = 0; i < rounds; i++) {
        String order = "ORD-C" + i;
        Callable<Res> c =
            () -> {
              start.await();
              return reserve(UUID.randomUUID().toString(), reserveBody(order, Map.of(sku, 4)));
            };
        futures.add(pool.submit(c));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Res> f : futures) {
        statuses.add(f.get().status());
      }
      assertThat(statuses).containsExactlyInAnyOrder(201, 422);
    } finally {
      pool.shutdownNow();
    }
    JsonNode a = availability(sku);
    assertThat(a.path("reserved").asInt()).isEqualTo(4);
    assertThat(a.path("available").asInt()).isEqualTo(1);
  }

  @Test
  void expiredHoldIsSweptAndPublished() throws Exception {
    String sku = newSku();
    handlingOn(sku);
    postReceipt(UUID.randomUUID().toString(), receipt("RCV-" + UUID.randomUUID(), sku, 3, 0));
    Res ok = reserve(UUID.randomUUID().toString(), reserveBody("ORD-EXP", Map.of(sku, 2)));
    assertThat(ok.status()).isEqualTo(201);
    String reservationId = ok.body().path("resultId").asString();
    assertThat(availability(sku).path("available").asInt()).isEqualTo(1);

    // 만료 시각을 과거로(TTL 최소 1분이라 시험에서 앞당김) → 만료 작업이 EXPIRED, R−q
    jdbc.update(
        "UPDATE reservation SET expires_at = ? WHERE reservation_id = ?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        reservationId);
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    JsonNode rv = get(ORCH, "/internal/reservations/" + reservationId).body();
    while (!"EXPIRED".equals(rv.path("state").asString()) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      rv = get(ORCH, "/internal/reservations/" + reservationId).body();
    }
    assertThat(rv.path("state").asString()).isEqualTo("EXPIRED");
    assertThat(rv.path("version").asInt()).isEqualTo(2);
    assertThat(availability(sku).path("reserved").asInt()).isZero();
    assertThat(availability(sku).path("available").asInt()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE topic = 'mfc.inventory.v1'"
                    + " AND type = 'InventoryHoldExpired' AND aggregateid = ?",
                Integer.class,
                reservationId))
        .isEqualTo(1);

    // 만료된 예약 확정 → 409, 해제 → 변화 없는 성공
    Res confirm =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + reservationId + "/confirmations",
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-EXP"));
    assertThat(confirm.status()).isEqualTo(409);
    Res release =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + reservationId + "/releases",
            UUID.randomUUID().toString(),
            Map.of("reason", "PAYMENT_FAILED"));
    assertThat(release.status()).isEqualTo(200);
    assertThat(release.body().path("appliedQty").asInt()).isZero();
    assertThat(availability(sku).path("available").asInt()).isEqualTo(3);
  }
}
