package com.pmart.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 개인정보 열람 기록(BO-25, 계약 0.2.0): 기록 → 조회, 같은 키 재시도(occurredAt 이 달라도) 재생, 다른 본문 409, 사유 없음 400, 추가
 * 전용(UPDATE·DELETE 거부).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      // 시험 클래스마다 컨테이너가 새로 뜨므로 ServiceBootIT 와 컨텍스트 캐시를 나눈다
      "pmart.test.context=pii-access-log"
    })
@Import(TestJwtConfiguration.class)
class PiiAccessLogIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.pmart.audit.application.PiiAccessLogPurger purger;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  Map<String, Object> body(String purpose, Instant at) {
    var b = new LinkedHashMap<String, Object>();
    b.put("occurredAt", at.toString());
    b.put(
        "actor", Map.of("actorId", "cs-1", "actorType", "OPERATOR", "roles", List.of("CS_AGENT")));
    b.put("subjectType", "CUSTOMER");
    b.put("subjectRef", "ACC-1");
    b.put("accessType", "VIEW");
    b.put("fields", List.of("NAME", "PHONE"));
    b.put("purpose", purpose);
    b.put("screen", "backoffice-web:order-detail");
    b.put("caseRef", "ORDER:ORD-1");
    return b;
  }

  ResponseEntity<String> post(String key, Object body) {
    return http.post()
        .uri("/internal/pii-access-logs")
        .header("Idempotency-Key", key)
        .header("X-Source-System", "order-service")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  void recordGetAndIdempotentRetry() {
    String key = "k-" + UUID.randomUUID();
    var first = post(key, body("고객 문의 배송지 확인", Instant.parse("2026-11-15T01:00:00Z")));
    assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(201);
    JsonNode r = mapper.readTree(first.getBody());
    String id = r.path("piiAccessLogId").asString();
    assertThat(id).startsWith("PAL-");

    // 재시도: occurredAt 이 달라도 같은 기록(200, 새 행 없음)
    var retry = post(key, body("고객 문의 배송지 확인", Instant.parse("2026-11-15T01:00:03Z")));
    assertThat(retry.getStatusCode().value()).isEqualTo(200);
    assertThat(mapper.readTree(retry.getBody()).path("piiAccessLogId").asString()).isEqualTo(id);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pii_access_log WHERE request_id = ?", Long.class, key))
        .isEqualTo(1L);

    // 같은 키·다른 사유 → 409
    var conflict = post(key, body("다른 사유", Instant.parse("2026-11-15T01:00:00Z")));
    assertThat(conflict.getStatusCode().value()).isEqualTo(409);

    // 조회
    var got =
        http.get().uri("/internal/pii-access-logs/{id}", id).retrieve().toEntity(String.class);
    assertThat(got.getStatusCode().value()).isEqualTo(200);
    JsonNode g = mapper.readTree(got.getBody());
    assertThat(g.path("piiAccessLogId").asString()).isEqualTo(id);
    assertThat(g.path("sourceSystem").asString()).isEqualTo("order-service");
    assertThat(g.path("requestId").asString()).isEqualTo(key);
    assertThat(g.path("actor").path("actorId").asString()).isEqualTo("cs-1");
    assertThat(g.path("actor").path("roles").path(0).asString()).isEqualTo("CS_AGENT");
    assertThat(g.path("subjectType").asString()).isEqualTo("CUSTOMER");
    assertThat(g.path("subjectRef").asString()).isEqualTo("ACC-1");
    assertThat(g.path("fields").toString()).isEqualTo("[\"NAME\",\"PHONE\"]");
    assertThat(g.path("purpose").asString()).isEqualTo("고객 문의 배송지 확인");
    assertThat(g.path("caseRef").asString()).isEqualTo("ORDER:ORD-1");
    assertThat(g.path("occurredAt").asString()).startsWith("2026-11-15T01:00");

    assertThat(
            http.get()
                .uri("/internal/pii-access-logs/PAL-NONE")
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);

    // 추가 전용: 고치기·지우기는 DB 가 거부한다
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pii_access_log SET purpose = 'x' WHERE pii_access_log_id = ?", id))
        .hasMessageContaining("추가만");
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM pii_access_log WHERE pii_access_log_id = ?", id))
        .hasMessageContaining("추가만");
  }

  @Test
  void purposeAndFieldsAreRequired() {
    var blank = post("k-" + UUID.randomUUID(), body("   ", Instant.now()));
    assertThat(blank.getStatusCode().value()).isEqualTo(400);
    var b = body("사유", Instant.now());
    b.remove("purpose");
    assertThat(post("k-" + UUID.randomUUID(), b).getStatusCode().value()).isEqualTo(400);
    var noFields = body("사유", Instant.now());
    noFields.put("fields", List.of());
    assertThat(post("k-" + UUID.randomUUID(), noFields).getStatusCode().value()).isEqualTo(400);
  }

  /** 보존 2년 뒤 파기(ADR-0062 결정 9, V3): 정리 작업만 지울 수 있고, 2년 안 기록은 남는다. 그냥 DELETE 는 여전히 거부. */
  @Test
  void purgeRemovesOnlyRecordsOlderThanRetention() {
    Instant old = Instant.now().minus(java.time.Duration.ofDays(3 * 365));
    Instant recent = Instant.now().minus(java.time.Duration.ofDays(30));
    assertThat(post("purge-old", body("오래된 열람", old)).getStatusCode().value()).isEqualTo(201);
    assertThat(post("purge-recent", body("최근 열람", recent)).getStatusCode().value()).isEqualTo(201);
    String count = "SELECT count(*) FROM pii_access_log WHERE request_id = ?";

    assertThat(purger.purge()).isGreaterThanOrEqualTo(1);

    assertThat(jdbc.queryForObject(count, Integer.class, "purge-old")).isZero();
    assertThat(jdbc.queryForObject(count, Integer.class, "purge-recent")).isEqualTo(1);
    // 정리 작업 밖의 DELETE 는 그대로 거부
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM pii_access_log WHERE request_id = 'purge-recent'"))
        .hasMessageContaining("추가만");
  }

  JsonNode staffGet(String token, String path) {
    var r =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    var node = mapper.createObjectNode();
    node.put("status", r.getStatusCode().value());
    node.set(
        "body", r.getBody() == null ? mapper.createObjectNode() : mapper.readTree(r.getBody()));
    return node;
  }

  /**
   * 직원용 열람 기록 조회(0.3.0, 2026-11-16): OPERATOR_ADMIN 만(상담원 403), 거르기(열람한 직원·정보주체·사건 참조·기간), 새 것부터,
   * 커서로 다음 쪽(겹침·빠짐 없음), 한 건 조회.
   */
  @Test
  void adminListsAccessLogsWithFiltersAndCursor() {
    String subject = "ACC-LIST-" + UUID.randomUUID().toString().substring(0, 6);
    Instant base = Instant.parse("2026-11-16T00:00:00Z");
    for (int i = 0; i < 5; i++) {
      var b = body("목록 시험 " + i, base.plusSeconds(i * 60L));
      b.put("subjectRef", subject);
      b.put("caseRef", i < 3 ? "ORDER:ORD-L1" : "ORDER:ORD-L2");
      assertThat(post("k-" + UUID.randomUUID(), b).getStatusCode().value()).isEqualTo(201);
    }
    String admin = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());
    String cs = TestJwt.operator("cs-1", List.of("CS_AGENT"), List.of());

    assertThat(staffGet(cs, "/pii-access-logs?subjectRef=" + subject).path("status").asInt())
        .isEqualTo(403);

    JsonNode p1 = staffGet(admin, "/pii-access-logs?subjectRef=" + subject + "&size=2");
    assertThat(p1.path("status").asInt()).isEqualTo(200);
    JsonNode items1 = p1.path("body").path("items");
    assertThat(items1).hasSize(2);
    assertThat(items1.get(0).path("purpose").asString()).isEqualTo("목록 시험 4");
    assertThat(items1.get(1).path("purpose").asString()).isEqualTo("목록 시험 3");
    String cursor = p1.path("body").path("page").path("nextCursor").asString();
    assertThat(cursor).isNotBlank();
    JsonNode p2 =
        staffGet(admin, "/pii-access-logs?subjectRef=" + subject + "&size=2&cursor=" + cursor);
    assertThat(p2.path("body").path("items").get(0).path("purpose").asString())
        .isEqualTo("목록 시험 2");
    JsonNode p3 =
        staffGet(
            admin,
            "/pii-access-logs?subjectRef="
                + subject
                + "&size=2&cursor="
                + p2.path("body").path("page").path("nextCursor").asString());
    assertThat(p3.path("body").path("items")).hasSize(1);
    assertThat(p3.path("body").path("page").path("nextCursor").isNull()).isTrue();

    assertThat(
            staffGet(admin, "/pii-access-logs?subjectRef=" + subject + "&caseRef=ORDER:ORD-L2")
                .path("body")
                .path("items"))
        .hasSize(2);
    assertThat(
            staffGet(
                    admin,
                    "/pii-access-logs?subjectRef="
                        + subject
                        + "&from=2026-11-16T00:01:00Z&to=2026-11-16T00:03:00Z")
                .path("body")
                .path("items"))
        .hasSize(2);
    assertThat(
            staffGet(admin, "/pii-access-logs?subjectRef=" + subject + "&actorId=cs-1")
                .path("body")
                .path("items"))
        .hasSize(5);
    assertThat(staffGet(admin, "/pii-access-logs?cursor=bad").path("status").asInt())
        .isEqualTo(400);

    String id = items1.get(0).path("piiAccessLogId").asString();
    JsonNode one = staffGet(admin, "/pii-access-logs/" + id);
    assertThat(one.path("status").asInt()).isEqualTo(200);
    assertThat(one.path("body").path("subjectRef").asString()).isEqualTo(subject);
    assertThat(staffGet(cs, "/pii-access-logs/" + id).path("status").asInt()).isEqualTo(403);
  }
}
