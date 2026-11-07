package com.pmart.mfc.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 재고 원장·예약(S1 a, ADR-0040): 수령 전기(receiptId 멱등, E 없음) → 검수·적치(executionId 멱등, 적치 뒤 E) → 가용 → 예약
 * all-or-nothing·동시 경합(TC-09)·LOT 조각(FEFO·최소 잔여일) → 같은 키 재생 → 확정·해제 → 만료 작업(InventoryHoldExpired
 * Outbox). 실제 PostgreSQL·Kafka, 시험 서비스 토큰.
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
  @Autowired KafkaTemplate<String, String> kafka;

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
    // 위치는 mfc-location 사건(MfcLocationStateChanged)으로 들어온다
    location("RCV-DOCK", "RECEIVING", "AMBIENT", "ACTIVE", 1);
    location("A-01-01", "STORAGE", "AMBIENT", "ACTIVE", 1);
    location("C-01-01", "STORAGE", "CHILLED", "ACTIVE", 1);
    awaitLocations(3);
  }

  void location(String code, String zone, String temp, String state, int version) {
    String id = "LOC-" + mfc + "-" + code;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("locationId", id);
    payload.put("mfcId", mfc);
    payload.put("locationCode", code);
    payload.put("zone", zone);
    payload.put("temperatureZone", temp);
    payload.put("state", state);
    payload.put("version", version);
    String json =
        EventEnvelopeFixtures.envelope("MfcLocationStateChanged")
            .from("mfc-location-service")
            .aggregate("Location", id, version)
            .payload(payload)
            .buildJson();
    kafka.send("mfc.location.v1", id, json).join();
  }

  void awaitLocations(int count) {
    awaitSql("SELECT count(*) FROM location_projection WHERE mfc_id = '" + mfc + "'", count);
  }

  void awaitSql(String sql, int expected) {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n = jdbc.queryForObject(sql, Integer.class);
      if (n != null && n == expected) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    assertThat(jdbc.queryForObject(sql, Integer.class)).isEqualTo(expected);
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

  /** SKU 투영(상품 이벤트 대신 시험 준비) — 보관 유형. */
  void projection(String sku, String storageType) {
    jdbc.update(
        "INSERT INTO sku_projection (sku, unit_type, storage_type, state, product_version, updated_at)"
            + " VALUES (?, 'E', ?, 'ACTIVE', 1, now()) ON CONFLICT (sku) DO NOTHING",
        sku,
        storageType);
  }

  static Map<String, Object> line(
      String sku, int normal, int quarantined, String lotRef, String expiry) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", sku);
    line.put("normalQty", normal);
    line.put("quarantinedQty", quarantined);
    line.put("pendingQty", 0);
    line.put("toLocationCode", "RCV-DOCK");
    if (lotRef != null) {
      line.put("sourceLotRef", lotRef);
    }
    if (expiry != null) {
      line.put("expiryDate", expiry);
    }
    return line;
  }

  Map<String, Object> receipt(String receiptId, String sku, int normal, int quarantined) {
    return receiptOf(receiptId, List.of(line(sku, normal, quarantined, null, null)));
  }

  Map<String, Object> receiptOf(String receiptId, List<Map<String, Object>> lines) {
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
        lines);
  }

  Res postReceipt(String key, Map<String, Object> body) {
    return post(INBOUND, "mfc-inbound-service", "/internal/receipts", key, body);
  }

  Res execute(String type, String fromLoc, String toLoc, List<Map<String, Object>> lines) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("executionType", type);
    body.put("mfcId", mfc);
    body.put("taskId", "TSK-" + UUID.randomUUID());
    body.put("executionId", "EXE-" + UUID.randomUUID());
    body.put("fromLocation", fromLoc);
    if (toLoc != null) {
      body.put("toLocation", toLoc);
    }
    body.put("lines", lines);
    return post(
        INBOUND,
        "mfc-inbound-service",
        "/internal/task-executions",
        UUID.randomUUID().toString(),
        body);
  }

  /** 수령 → 수령 결과의 lotId 로 정상분 검수(NORMAL) → 선반 적치. 적치 뒤에만 판매 가능(E). */
  void stocked(String receiptId, List<Map<String, Object>> lines) {
    for (Map<String, Object> l : lines) {
      projection((String) l.get("sku"), "AMBIENT");
    }
    Res r = postReceipt(UUID.randomUUID().toString(), receiptOf(receiptId, lines));
    assertThat(r.status()).isEqualTo(201);
    JsonNode result = get(INBOUND, "/internal/receipts/" + receiptId).body();
    for (JsonNode l : result.path("lines")) {
      int normal = l.path("normalQty").asInt();
      if (normal == 0) {
        continue;
      }
      var el =
          List.<Map<String, Object>>of(
              Map.of(
                  "sku",
                  l.path("sku").asString(),
                  "lotId",
                  l.path("lotId").asString(),
                  "qty",
                  normal,
                  "resultQuality",
                  "NORMAL"));
      assertThat(execute("INSPECT", "RCV-DOCK", null, el).status()).isEqualTo(201);
      var pl =
          List.<Map<String, Object>>of(
              Map.of(
                  "sku",
                  l.path("sku").asString(),
                  "lotId",
                  l.path("lotId").asString(),
                  "qty",
                  normal));
      String shelf =
          "CHILLED"
                  .equals(
                      jdbc.queryForObject(
                          "SELECT storage_type FROM sku_projection WHERE sku = ?",
                          String.class,
                          l.path("sku").asString()))
              ? "C-01-01"
              : "A-01-01";
      assertThat(execute("PUTAWAY", "RCV-DOCK", shelf, pl).status()).isEqualTo(201);
    }
  }

  void stocked(String sku, int normal) {
    stocked("RCV-" + UUID.randomUUID(), List.of(line(sku, normal, 0, null, null)));
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

    // 수령만으로는 판매 가능(E) 0 — 검수·적치 전(IV-02)
    JsonNode a = availability(sku);
    assertThat(a.path("onHand").asInt()).isEqualTo(7);
    assertThat(a.path("quarantined").asInt()).isEqualTo(1);
    assertThat(a.path("sellable").asInt()).isZero();
    assertThat(a.path("available").asInt()).isZero();
    assertThat(a.path("handlingEnabled").asBoolean()).isFalse();

    // 수령 결과: 줄별 inventory lotId(상류 표기·기한 없음 → 추적 미확인 LOT)
    Res result = get(INBOUND, "/internal/receipts/" + receiptId);
    assertThat(result.status()).isEqualTo(200);
    String lotId = result.body().path("lines").path(0).path("lotId").asString();
    assertThat(lotId).startsWith("LOT-");
    assertThat(get(INBOUND, "/internal/receipts/RCV-none").status()).isEqualTo(404);

    // 검수 정상 5 → 아직 E 0(적치 대기), 적치 → E 5
    var inspectLines =
        List.<Map<String, Object>>of(
            Map.of("sku", sku, "lotId", lotId, "qty", 5, "resultQuality", "NORMAL"));
    Res inspected = execute("INSPECT", "RCV-DOCK", null, inspectLines);
    assertThat(inspected.status()).isEqualTo(201);
    assertThat(availability(sku).path("sellable").asInt()).isZero();
    // 검수 대기 잔량보다 많이 → 422(위치 잔량 부족)
    var tooMany =
        List.<Map<String, Object>>of(
            Map.of("sku", sku, "lotId", lotId, "qty", 1, "resultQuality", "NORMAL"));
    assertThat(execute("INSPECT", "RCV-DOCK", null, tooMany).status()).isEqualTo(422);
    var putLines = List.<Map<String, Object>>of(Map.of("sku", sku, "lotId", lotId, "qty", 5));
    // 보관 유형을 모르면 적치 CONFIG_REQUIRED, 온도대가 다르면 409, 수령 구역에 적치하면 409
    Res noType = execute("PUTAWAY", "RCV-DOCK", "A-01-01", putLines);
    assertThat(noType.status()).isEqualTo(422);
    assertThat(noType.body().path("code").asString()).isEqualTo("CONFIG_REQUIRED");
    projection(sku, "AMBIENT");
    Res wrongTemp = execute("PUTAWAY", "RCV-DOCK", "C-01-01", putLines);
    assertThat(wrongTemp.status()).isEqualTo(409);
    assertThat(wrongTemp.body().path("code").asString()).isEqualTo("TEMPERATURE_ZONE_MISMATCH");
    assertThat(execute("PUTAWAY", "RCV-DOCK", "RCV-DOCK", putLines).body().path("code").asString())
        .isEqualTo("LOCATION_ZONE_MISMATCH");
    Res put = execute("PUTAWAY", "RCV-DOCK", "A-01-01", putLines);
    assertThat(put.status()).isEqualTo(201);
    a = availability(sku);
    assertThat(a.path("onHand").asInt()).isEqualTo(7);
    assertThat(a.path("sellable").asInt()).isEqualTo(5);
    assertThat(a.path("available").asInt()).isEqualTo(5);
    // 적치 원장: 수령 위치 −5, 선반 +5(E+5)
    JsonNode putCommit =
        get(INBOUND, "/internal/commits/" + put.body().path("resultId").asString()).body();
    assertThat(putCommit.path("commitType").asString()).isEqualTo("TASK_EXECUTION");
    assertThat(putCommit.path("entries").toString()).contains("A-01-01").contains("STORED");
    // 다른 SKU 의 lotId → 400, 예약 없는 PICK → 400(6b: 예약·주문 줄 필수), MOVE 는 아직 501
    var wrongLot = List.<Map<String, Object>>of(Map.of("sku", newSku(), "lotId", lotId, "qty", 1));
    assertThat(execute("PUTAWAY", "RCV-DOCK", "A-01-01", wrongLot).status()).isEqualTo(400);
    assertThat(execute("PICK", "A-01-01", "WORK-1", putLines).status()).isEqualTo(400);
    assertThat(execute("MOVE", "A-01-01", "WORK-1", putLines).status()).isEqualTo(501);
    // 원장 행이 없는 SKU 는 0
    assertThat(availability(newSku()).path("available").asInt()).isZero();

    // 전기 조회
    Res commit = get(INBOUND, "/internal/commits/" + commitId);
    assertThat(commit.status()).isEqualTo(200);
    assertThat(commit.body().path("commitType").asString()).isEqualTo("TIN");
    assertThat(commit.body().path("sourceRef").asString()).isEqualTo(receiptId);
    assertThat(commit.body().path("entries")).hasSize(3); // 검수 대기 H+5, 격리 H+1·Q+1

    // 수령 위치: 미등록 422, 선반 구역 409, 폐쇄 409
    Map<String, Object> badLine = line(sku, 1, 0, null, null);
    badLine.put("toLocationCode", "NOWHERE");
    Res unknown =
        postReceipt(
            UUID.randomUUID().toString(), receiptOf("RCV-" + UUID.randomUUID(), List.of(badLine)));
    assertThat(unknown.status()).isEqualTo(422);
    assertThat(unknown.body().path("code").asString()).isEqualTo("LOCATION_NOT_REGISTERED");
    badLine.put("toLocationCode", "A-01-01");
    assertThat(
            postReceipt(
                    UUID.randomUUID().toString(),
                    receiptOf("RCV-" + UUID.randomUUID(), List.of(badLine)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("LOCATION_ZONE_MISMATCH");
    location("RCV-DOCK", "RECEIVING", "AMBIENT", "CLOSED", 2);
    awaitSql(
        "SELECT count(*) FROM location_projection WHERE mfc_id = '"
            + mfc
            + "' AND state = 'CLOSED'",
        1);
    location("RCV-DOCK", "RECEIVING", "AMBIENT", "ACTIVE", 1); // 오래된 버전은 무시
    Res closed =
        postReceipt(UUID.randomUUID().toString(), receipt("RCV-" + UUID.randomUUID(), sku, 1, 0));
    assertThat(closed.status()).isEqualTo(409);
    assertThat(closed.body().path("code").asString()).isEqualTo("LOCATION_CLOSED");

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
    stocked(a, 5);

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
    assertThat(rv.body().path("parts").path(0).path("lotId").asString()).startsWith("LOT-");
    assertThat(rv.body().path("parts").path(0).path("state").asString()).isEqualTo("HELD");
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
    assertThat(afterConfirm.path("parts").path(0).path("state").asString()).isEqualTo("CONFIRMED");
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
  void reservationSplitsLinesAcrossLotsFefoAndRespectsMinimumRemainingDays() {
    // 냉장 우유(최소 잔여일 2일): 기한 +1일 3개, +3일 2개, +10일 5개 / 배송 약속일 = 오늘
    String milk = newSku();
    handlingOn(milk);
    projection(milk, "CHILLED");
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    stocked(
        "RCV-" + UUID.randomUUID(),
        List.of(
            line(milk, 3, 0, "M-OLD", today.plusDays(1).toString()),
            line(milk, 2, 0, "M-MID", today.plusDays(3).toString()),
            line(milk, 5, 0, "M-NEW", today.plusDays(10).toString())));
    assertThat(availability(milk).path("available").asInt()).isEqualTo(10);

    // 4개 → 기한 +1일 LOT 은 최소 잔여일 미달로 제외, +3일 2개 + +10일 2개(줄당 LOT 수 제한 없음)
    Res ok =
        reserve(
            UUID.randomUUID().toString(),
            Map.of(
                "orderId",
                "ORD-FEFO",
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("orderLineId", "L1", "sku", milk, "qty", 4))));
    assertThat(ok.status()).isEqualTo(201);
    JsonNode parts =
        get(ORCH, "/internal/reservations/" + ok.body().path("resultId").asString())
            .body()
            .path("parts");
    assertThat(parts).hasSize(2);
    assertThat(parts.path(0).path("qty").asInt() + parts.path(1).path("qty").asInt()).isEqualTo(4);
    assertThat(parts.path(0).path("orderLineId").asString()).isEqualTo("L1");
    String midLot = parts.path(0).path("lotId").asString();
    assertThat(parts.path(0).path("qty").asInt()).isEqualTo(2);
    assertThat(parts.path(1).path("lotId").asString()).isNotEqualTo(midLot);

    // 센터 가용은 6(기한 임박 3 포함)이지만 적격은 3 → 4개 요청은 NO_ELIGIBLE_LOT
    assertThat(availability(milk).path("available").asInt()).isEqualTo(6);
    Res noLot = reserve(UUID.randomUUID().toString(), reserveBody("ORD-FEFO2", Map.of(milk, 4)));
    assertThat(noLot.status()).isEqualTo(422);
    assertThat(noLot.body().path("details").path(0).path("reason").asString())
        .startsWith("NO_ELIGIBLE_LOT")
        .contains("requestedQty=4;eligibleQty=3");
  }

  /** 상품별 소비기한 보장(ADR-0041) — 상품 이벤트 대신 투영 열을 직접 채운다(시험 준비). */
  void guarantee(String sku, Integer minRemainingDays, Integer maxDaysSinceManufacture) {
    jdbc.update(
        "UPDATE sku_projection SET shelf_min_remaining_days = ?, shelf_max_days_since_mfg = ?"
            + " WHERE sku = ?",
        minRemainingDays,
        maxDaysSinceManufacture,
        sku);
  }

  /** 수령 → 검수(제조 LOT·소비기한·제조일 확인, LOT 분리) → 선반 적치. */
  void stockedWithManufacture(String sku, int qty, String lotRef, String expiry, String mfg) {
    String receiptId = "RCV-" + UUID.randomUUID();
    assertThat(postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, qty, 0)).status())
        .isEqualTo(201);
    String pending =
        get(INBOUND, "/internal/receipts/" + receiptId)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("sku", sku);
    l.put("lotId", pending);
    l.put("qty", qty);
    l.put("resultQuality", "NORMAL");
    l.put("sourceLotRef", lotRef);
    if (expiry != null) {
      l.put("expiryDate", expiry);
    }
    if (mfg != null) {
      l.put("manufactureDate", mfg);
    }
    Res inspected = execute("INSPECT", "RCV-DOCK", null, List.of(l));
    assertThat(inspected.status()).isEqualTo(201);
    String lot = null;
    for (JsonNode e :
        get(INBOUND, "/internal/commits/" + inspected.body().path("resultId").asString())
            .body()
            .path("entries")) {
      if (e.path("delta").asInt() > 0) {
        lot = e.path("lotId").asString();
      }
    }
    assertThat(
            execute(
                    "PUTAWAY",
                    "RCV-DOCK",
                    "C-01-01",
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", qty)))
                .status())
        .isEqualTo(201);
  }

  @Test
  void productShelfLifeGuaranteeDecidesEligibleLots() {
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    // 1) 냉장이지만 고시 "주문일 기준 7일 이상 남은 상품" → 기본 2일 대신 7일
    String yogurt = newSku();
    handlingOn(yogurt);
    projection(yogurt, "CHILLED");
    guarantee(yogurt, 7, null);
    stockedWithManufacture(yogurt, 3, "Y-SHORT", today.plusDays(5).toString(), null);
    stockedWithManufacture(yogurt, 2, "Y-LONG", today.plusDays(9).toString(), null);
    Res noLot = reserve(UUID.randomUUID().toString(), reserveBody("ORD-SL1", Map.of(yogurt, 3)));
    assertThat(noLot.status()).isEqualTo(422);
    assertThat(noLot.body().path("details").path(0).path("reason").asString())
        .contains("requestedQty=3;eligibleQty=2");
    assertThat(
            reserve(UUID.randomUUID().toString(), reserveBody("ORD-SL2", Map.of(yogurt, 2)))
                .status())
        .isEqualTo(201);

    // 2) 고시 "주문일 기준 2일 이내 제조된 상품"(빵): 제조일 확인된 LOT 만, 오래된 제조분 제외
    String bread = newSku();
    handlingOn(bread);
    projection(bread, "CHILLED");
    guarantee(bread, null, 2);
    stockedWithManufacture(
        bread, 4, "B-OLD", today.plusDays(10).toString(), today.minusDays(3).toString());
    stockedWithManufacture(
        bread, 1, "B-NEW", today.plusDays(10).toString(), today.minusDays(1).toString());
    stockedWithManufacture(bread, 5, "B-UNKNOWN", today.plusDays(10).toString(), null);
    Res bad = reserve(UUID.randomUUID().toString(), reserveBody("ORD-SL3", Map.of(bread, 2)));
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().path("details").path(0).path("reason").asString())
        .contains("requestedQty=2;eligibleQty=1");
    Res one = reserve(UUID.randomUUID().toString(), reserveBody("ORD-SL4", Map.of(bread, 1)));
    assertThat(one.status()).isEqualTo(201);
    // 센터 재고 상세가 LOT 제조일을 보여 준다
    String op = TestJwt.operator("op", List.of(), List.of(mfc));
    JsonNode lots = get(op, "/mfcs/" + mfc + "/stock?sku=" + bread).body().path("lots");
    assertThat(lots.toString()).contains(today.minusDays(1).toString());
  }

  /** PICK·PACK 실행(fulfillment 가 부르는 모양). */
  Res pickPack(
      String type, String reservationId, String from, String to, List<Map<String, Object>> lines) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("executionType", type);
    body.put("mfcId", mfc);
    body.put("taskId", "TSK-" + UUID.randomUUID());
    body.put("executionId", "EXE-" + UUID.randomUUID());
    body.put("reservationId", reservationId);
    body.put("fromLocation", from);
    if (to != null) {
      body.put("toLocation", to);
    }
    body.put("lines", lines);
    return post(
        INBOUND,
        "mfc-fulfillment-service",
        "/internal/task-executions",
        UUID.randomUUID().toString(),
        body);
  }

  @Test
  void pickAndPackMoveReservedUnitsWithoutChangingTotals() {
    location("PAK-A-01", "PACKING", "AMBIENT", "ACTIVE", 1);
    awaitLocations(4);
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 10);
    Res held =
        reserve(
            UUID.randomUUID().toString(),
            Map.of(
                "orderId",
                "ORD-PICK",
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("orderLineId", "L1", "sku", sku, "qty", 4))));
    String reservationId = held.body().path("resultId").asString();

    // 확정 전에는 집품 못 한다
    JsonNode plan = get(ORCH, "/internal/reservations/" + reservationId + "/pick-plan").body();
    JsonNode part = plan.path("parts").path(0);
    String lot = part.path("lotId").asString();
    assertThat(part.path("qty").asInt()).isEqualTo(4);
    assertThat(part.path("locations").path(0).path("locationCode").asString()).isEqualTo("A-01-01");
    assertThat(part.path("locations").path(0).path("qty").asInt()).isEqualTo(10);
    Map<String, Object> line = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 3);
    assertThat(pickPack("PICK", reservationId, "A-01-01", "PAK-A-01", List.of(line)).status())
        .isEqualTo(409);
    assertThat(
            post(
                    ORCH,
                    "order-orchestration-service",
                    "/internal/reservations/" + reservationId + "/confirmations",
                    UUID.randomUUID().toString(),
                    Map.of("orderId", "ORD-PICK", "paymentAttemptId", "PAY-P"))
                .status())
        .isEqualTo(200);

    // 포장 구역이 아닌 도착지, 예약에 없는 LOT, 남은 양 초과는 거절
    assertThat(pickPack("PICK", reservationId, "A-01-01", "C-01-01", List.of(line)).status())
        .isEqualTo(409);
    Res wrongLot =
        pickPack(
            "PICK",
            reservationId,
            "A-01-01",
            "PAK-A-01",
            List.of(Map.of("orderLineId", "L1", "sku", sku, "lotId", "LOT-NONE", "qty", 1)));
    assertThat(wrongLot.status()).isIn(400, 422);
    Res tooMany =
        pickPack(
            "PICK",
            reservationId,
            "A-01-01",
            "PAK-A-01",
            List.of(Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 5)));
    assertThat(tooMany.status()).isEqualTo(422);
    assertThat(tooMany.body().path("code").asString()).isEqualTo("PICK_QTY_EXCEEDED");

    // 3 집품 → 총량 불변(H10/E10/R4/A6), 선반 7·포장대 PICKED 3
    assertThat(pickPack("PICK", reservationId, "A-01-01", "PAK-A-01", List.of(line)).status())
        .isEqualTo(201);
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(10);
    assertThat(av.path("sellable").asInt()).isEqualTo(10);
    assertThat(av.path("reserved").asInt()).isEqualTo(4);
    assertThat(av.path("available").asInt()).isEqualTo(6);
    JsonNode after = get(ORCH, "/internal/reservations/" + reservationId + "/pick-plan").body();
    assertThat(after.path("parts").path(0).path("pickedQty").asInt()).isEqualTo(3);
    assertThat(after.path("parts").path(0).path("locations").path(0).path("qty").asInt())
        .isEqualTo(7);

    // 포장은 집품한 만큼만, 같은 포장대에서 PICKED → PACKED
    Map<String, Object> pack = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 4);
    assertThat(pickPack("PACK", reservationId, "PAK-A-01", null, List.of(pack)).status())
        .isEqualTo(422);
    assertThat(pickPack("PACK", reservationId, "PAK-A-01", null, List.of(line)).status())
        .isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT qty FROM stock_unit WHERE mfc_id = ? AND sku = ? AND location_code ="
                    + " 'PAK-A-01' AND work_state = 'PACKED'",
                Integer.class,
                mfc,
                sku))
        .isEqualTo(3);
    assertThat(availability(sku).path("available").asInt()).isEqualTo(6);

    // 집품 뒤에는 단순 해제 불가(IV-08 전)
    Res release =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + reservationId + "/releases",
            UUID.randomUUID().toString(),
            Map.of("reason", "CANCEL_BEFORE_PICK"));
    assertThat(release.status()).isEqualTo(409);
    assertThat(release.body().path("code").asString()).isEqualTo("RESERVATION_PICKED");
  }

  @Test
  void inspectionSplitsTheReceivedLotByManufacturerLotAndExpiry() {
    // 수령은 상품·수량만(LOT 미확정) → 품질검사에서 제조 LOT·소비기한별로 나누고 격리분을 가른다
    String sku = newSku();
    projection(sku, "AMBIENT");
    String receiptId = "RCV-" + UUID.randomUUID();
    assertThat(postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, 6, 0)).status())
        .isEqualTo(201);
    String pending =
        get(INBOUND, "/internal/receipts/" + receiptId)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("sku", sku);
    a.put("lotId", pending);
    a.put("qty", 3);
    a.put("resultQuality", "NORMAL");
    a.put("sourceLotRef", "MFG-A");
    a.put("expiryDate", today.plusDays(60).toString());
    Map<String, Object> b = new LinkedHashMap<>(a);
    b.put("qty", 2);
    b.put("sourceLotRef", "MFG-B");
    b.put("expiryDate", today.plusDays(90).toString());
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("sku", sku);
    c.put("lotId", pending);
    c.put("qty", 1);
    c.put("resultQuality", "QUARANTINED");
    Res inspected = execute("INSPECT", "RCV-DOCK", null, List.of(a, b, c));
    assertThat(inspected.status()).isEqualTo(201);
    JsonNode entries =
        get(INBOUND, "/internal/commits/" + inspected.body().path("resultId").asString())
            .body()
            .path("entries");
    java.util.Set<String> normalLots = new java.util.TreeSet<>();
    for (JsonNode e : entries) {
      if ("H".equals(e.path("symbol").asString())
          && e.path("delta").asInt() > 0
          && "NORMAL".equals(e.path("qualityState").asString())) {
        normalLots.add(e.path("lotId").asString());
      }
    }
    assertThat(normalLots).hasSize(2).doesNotContain(pending);
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(6);
    assertThat(av.path("quarantined").asInt()).isEqualTo(1);
    assertThat(av.path("sellable").asInt()).isZero();
    // 같은 제조 LOT·기한은 다음 수령에서도 같은 LOT
    String receipt2 = "RCV-" + UUID.randomUUID();
    postReceipt(UUID.randomUUID().toString(), receipt(receipt2, sku, 1, 0));
    String pending2 =
        get(INBOUND, "/internal/receipts/" + receipt2)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    Map<String, Object> again = new LinkedHashMap<>(a);
    again.put("lotId", pending2);
    again.put("qty", 1);
    Res second = execute("INSPECT", "RCV-DOCK", null, List.of(again));
    JsonNode e2 =
        get(INBOUND, "/internal/commits/" + second.body().path("resultId").asString())
            .body()
            .path("entries");
    String reused = null;
    for (JsonNode e : e2) {
      if (e.path("delta").asInt() > 0) {
        reused = e.path("lotId").asString();
      }
    }
    assertThat(normalLots).contains(reused);
  }

  @Test
  void correctionMovesLotAndLocationWithoutChangingTotalsButNotReservedQuantity() {
    // 이관처럼 추적 미확인 LOT 5개(선반) + 예약 2 → 3개만 정정 가능(R ≤ E)
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 5);
    assertThat(
            reserve(UUID.randomUUID().toString(), reserveBody("ORD-COR", Map.of(sku, 2))).status())
        .isEqualTo(201);
    String op = TestJwt.operator("op", List.of(), List.of(mfc));
    JsonNode stock = get(op, "/mfcs/" + mfc + "/stock?sku=" + sku).body();
    assertThat(stock.path("lots")).hasSize(1);
    String lot = stock.path("lots").path(0).path("lotId").asString();
    assertThat(stock.path("lots").path(0).path("tracking").asString()).isEqualTo("UNTRACKED");
    assertThat(stock.path("lots").path(0).path("available").asInt()).isEqualTo(3);
    String expiry = java.time.LocalDate.now().plusDays(40).toString();
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", sku);
    line.put("lotId", lot);
    line.put("fromLocation", "A-01-01");
    line.put("qualityState", "NORMAL");
    line.put("workState", "STORED");
    line.put("qty", 4);
    line.put("toSourceLotRef", "SEED-LOT-1");
    line.put("toExpiryDate", expiry);
    Map<String, Object> body =
        Map.of("reason", "시드 LOT 정정", "approvalRef", "seed-v2", "lines", List.of(line));
    String path = "/mfcs/" + mfc + "/stock-corrections";
    Res tooMuch = post(op, "backoffice-web", path, UUID.randomUUID().toString(), body);
    assertThat(tooMuch.status()).isEqualTo(409);
    assertThat(tooMuch.body().path("code").asString()).isEqualTo("INVARIANT_VIOLATION");
    line.put("qty", 3);
    Res ok = post(op, "backoffice-web", path, UUID.randomUUID().toString(), body);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
    JsonNode after = get(op, "/mfcs/" + mfc + "/stock?sku=" + sku).body();
    assertThat(after.path("lots")).hasSize(2);
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(5);
    assertThat(av.path("sellable").asInt()).isEqualTo(5);
    assertThat(av.path("reserved").asInt()).isEqualTo(2);
    assertThat(
            get(INBOUND, "/internal/commits/" + ok.body().path("resultId").asString())
                .body()
                .path("commitType")
                .asString())
        .isEqualTo("CORRECTION");
    // 다른 센터 운영자·근거 없음
    String other = TestJwt.operator("op2", List.of(), List.of("MFC-OTHER"));
    assertThat(post(other, "backoffice-web", path, UUID.randomUUID().toString(), body).status())
        .isEqualTo(403);
    assertThat(
            post(
                    op,
                    "backoffice-web",
                    path,
                    UUID.randomUUID().toString(),
                    Map.of("reason", "x", "approvalRef", "", "lines", List.of(line)))
                .status())
        .isEqualTo(422);
  }

  @Test
  void concurrentReservationsOnLastUnitsOnlyOneSucceeds() throws Exception {
    // TC-09: H5/E5/R0/A5 에 주문 A4·B4 동시 → 하나만 성공(R4/A1)
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 5);
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
    stocked(sku, 3);
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
