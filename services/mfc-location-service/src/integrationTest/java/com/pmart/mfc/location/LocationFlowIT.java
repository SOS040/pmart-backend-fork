package com.pmart.mfc.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 위치 마스터(ADR-0040 2단계, TC-03 영역/위치 구분의 위치 쪽): 등록(센터 운영자만, 코드 센터 안 유일) → 속성 변경(코드 불변, 버전) → 상태
 * 전이(ACTIVE↔CLOSED·COUNT_LOCKED, RETIRED 는 DELETE) → 적치 후보(온도대·ACTIVE·선반) → MfcLocationStateChanged
 * Outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class LocationFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  RestClient http;
  String mfc;
  String op;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    mfc = "MFC-IT-" + UUID.randomUUID().toString().substring(0, 8);
    op = TestJwt.operator("op-1", List.of(), List.of(mfc));
  }

  record Res(int status, JsonNode body) {}

  Res send(HttpMethod m, String token, String path, Object body, Integer expectedVersion) {
    var spec =
        http.method(m)
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                  if (expectedVersion != null) {
                    h.set("X-Expected-Version", String.valueOf(expectedVersion));
                  }
                })
            .contentType(MediaType.APPLICATION_JSON);
    var e = (body == null ? spec : spec.body(body)).retrieve().toEntity(String.class);
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

  Map<String, Object> body(String code, String zone, String temp, Integer seq) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("mfcId", mfc);
    b.put("locationCode", code);
    b.put("zone", zone);
    b.put("temperatureZone", temp);
    if (seq != null) {
      b.put("pickSequence", seq);
    }
    return b;
  }

  @Test
  void registerChangeAndListLocations() {
    Res created =
        send(
            HttpMethod.POST, op, "/locations", body("C-S1-01-1-01", "STORAGE", "CHILLED", 2), null);
    assertThat(created.status()).isEqualTo(201);
    String id = created.body().path("locationId").asString();
    assertThat(id).startsWith("LOC-");
    assertThat(created.body().path("state").asString()).isEqualTo("ACTIVE");
    assertThat(created.body().path("version").asInt()).isEqualTo(1);

    // 같은 센터 같은 코드 → 409, 다른 센터 운영자 → 403
    Res dup =
        send(
            HttpMethod.POST,
            op,
            "/locations",
            body("C-S1-01-1-01", "STORAGE", "CHILLED", null),
            null);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().path("code").asString()).isEqualTo("LOCATION_CODE_DUPLICATE");
    // 코드 문법(Q-03, ADR-0042): 옛 자유 형식·온도대 불일치 → 400, 구역은 코드에서 채운다
    Res legacy =
        send(HttpMethod.POST, op, "/locations", body("C-01-01", "STORAGE", "CHILLED", null), null);
    assertThat(legacy.status()).isEqualTo(400);
    assertThat(legacy.body().path("code").asString()).isEqualTo("LOCATION_CODE_INVALID");
    assertThat(
            send(
                    HttpMethod.POST,
                    op,
                    "/locations",
                    body("A-S1-01-1-02", "STORAGE", "CHILLED", null),
                    null)
                .status())
        .isEqualTo(400);
    assertThat(created.body().path("areaCode").asString()).isEqualTo("S1");
    String other = TestJwt.operator("op-2", List.of(), List.of("MFC-OTHER"));
    assertThat(
            send(
                    HttpMethod.POST,
                    other,
                    "/locations",
                    body("A-S1-09-1-01", "STORAGE", "AMBIENT", null),
                    null)
                .status())
        .isEqualTo(403);

    // 속성 변경(동선 순서), 코드 변경 → 400, 낡은 버전 → 409
    Res updated =
        send(
            HttpMethod.PUT,
            op,
            "/locations/" + id,
            body("C-S1-01-1-01", "STORAGE", "CHILLED", 1),
            1);
    assertThat(updated.status()).isEqualTo(200);
    assertThat(updated.body().path("version").asInt()).isEqualTo(2);
    assertThat(updated.body().path("pickSequence").asInt()).isEqualTo(1);
    assertThat(
            send(
                    HttpMethod.PUT,
                    op,
                    "/locations/" + id,
                    body("C-S1-01-1-02", "STORAGE", "CHILLED", 1),
                    2)
                .status())
        .isEqualTo(400);
    assertThat(
            send(
                    HttpMethod.PUT,
                    op,
                    "/locations/" + id,
                    body("C-S1-01-1-01", "STORAGE", "CHILLED", 3),
                    1)
                .status())
        .isEqualTo(409);

    // 상태: ACTIVE → CLOSED → (COUNT_LOCKED 직행 불가) → ACTIVE, RETIRED 는 상태 변경으로 불가
    String sc = "/locations/" + id + "/state-changes";
    Res closed =
        send(HttpMethod.POST, op, sc, Map.of("targetState", "CLOSED", "reason", "선반 수리"), null);
    assertThat(closed.status()).isEqualTo(202);
    assertThat(closed.body().path("version").asInt()).isEqualTo(3);
    assertThat(
            send(
                    HttpMethod.POST,
                    op,
                    sc,
                    Map.of("targetState", "COUNT_LOCKED", "reason", "x"),
                    null)
                .status())
        .isEqualTo(409);
    assertThat(
            send(HttpMethod.POST, op, sc, Map.of("targetState", "RETIRED", "reason", "x"), null)
                .status())
        .isEqualTo(409);
    assertThat(
            send(HttpMethod.POST, op, sc, Map.of("targetState", "ACTIVE", "reason", "수리 끝"), null)
                .status())
        .isEqualTo(202);

    // 적치 후보: 냉장·ACTIVE·선반/피킹만, 동선 순서
    send(HttpMethod.POST, op, "/locations", body("A-S1-01-1-01", "STORAGE", "AMBIENT", 1), null);
    send(HttpMethod.POST, op, "/locations", body("C-P1-00-1-09", "PICKING", "CHILLED", 0), null);
    send(HttpMethod.POST, op, "/locations", body("RCV-C-01", "RECEIVING", "CHILLED", null), null);
    Res cand =
        get(op, "/putaway-candidates?mfcId=" + mfc + "&sku=E-00000001&temperatureZone=CHILLED");
    assertThat(cand.status()).isEqualTo(200);
    assertThat(cand.body().path("items").findValuesAsString("locationCode"))
        .containsExactly("C-P1-00-1-09", "C-S1-01-1-01");
    assertThat(get(op, "/putaway-candidates?mfcId=" + mfc + "&sku=E-00000001").status())
        .isEqualTo(400);

    // 목록·커서, 서비스 토큰 읽기 허용, 다른 센터 운영자 읽기 403
    Res page1 = get(op, "/locations?mfcId=" + mfc + "&size=2");
    assertThat(page1.body().path("items")).hasSize(2);
    String cursor = page1.body().path("page").path("nextCursor").asString();
    Res page2 = get(op, "/locations?mfcId=" + mfc + "&size=2&cursor=" + cursor);
    assertThat(page2.body().path("items")).hasSize(2);
    assertThat(page2.body().path("page").path("nextCursor").isNull()).isTrue();
    assertThat(get(TestJwt.service("mfc-inventory-service"), "/locations/" + id).status())
        .isEqualTo(200);
    assertThat(get(other, "/locations/" + id).status()).isEqualTo(403);

    // 사건: 등록 4 + 속성 변경 1 + 상태 2 = 이 위치 4건(v1~v4), 전체 속성
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT payload::text AS p FROM outbox_event WHERE topic = 'mfc.location.v1'"
                + " AND aggregateid = ? ORDER BY (payload->>'aggregateVersion')::int",
            id);
    assertThat(rows).hasSize(4);
    JsonNode last = read(rows.get(3).get("p").toString()).path("payload");
    assertThat(last.path("locationCode").asString()).isEqualTo("C-S1-01-1-01");
    assertThat(last.path("zone").asString()).isEqualTo("STORAGE");
    assertThat(last.path("temperatureZone").asString()).isEqualTo("CHILLED");
    assertThat(last.path("state").asString()).isEqualTo("ACTIVE");
    assertThat(last.path("previousState").asString()).isEqualTo("CLOSED");
    assertThat(last.path("version").asInt()).isEqualTo(4);

    // 다시 알림: 상태 변화 없이 같은 버전
    Res rep =
        send(
            HttpMethod.POST,
            TestJwt.service("ops"),
            "/internal/location-republications?mfcId=" + mfc,
            null,
            null);
    assertThat(rep.status()).isEqualTo(202);
    assertThat(rep.body().path("appliedQty").asInt()).isEqualTo(4);
    assertThat(get(op, "/locations/" + id).body().path("version").asInt()).isEqualTo(4);
  }
}
