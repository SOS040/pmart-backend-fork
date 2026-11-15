package com.pmart.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.test.PostgresKafkaContainerSupport;
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
class PiiAccessLogIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

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
}
