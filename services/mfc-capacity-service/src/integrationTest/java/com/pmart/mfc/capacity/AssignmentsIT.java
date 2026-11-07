package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

/** 수동 배정(WK-02·WK-05, 슬라이스 6b): 감독자만, 활성 1명·버전 증가, 재지정 SUPERSEDED, 낡은 버전 409, 사건 발행, 조회. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class AssignmentsIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-ASG-1";
  static final String SUPERVISOR =
      TestJwt.operator("sup-1", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String WORKER = TestJwt.operator("wrk-1", List.of("MFC_WORKER"), List.of(MFC));
  static final String OTHER_SUP =
      TestJwt.operator("sup-9", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));

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

  record Res(int status, JsonNode body) {}

  Res assign(String token, Map<String, Object> body, @Nullable Integer expected) {
    var e =
        http.post()
            .uri("/assignments")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                  if (expected != null) {
                    h.set("X-Expected-Version", expected.toString());
                  }
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .toEntity(String.class);
    return res(e);
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return res(e);
  }

  Res res(ResponseEntity<String> e) {
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> body(String taskId, String worker) {
    return Map.of("mfcId", MFC, "taskId", taskId, "taskType", "PICK", "workerId", worker);
  }

  @Test
  void supervisorAssignsAndReassignsWithVersions() {
    String task = "TSK-" + UUID.randomUUID();
    // 작업자·다른 센터 감독자는 배정 못 한다
    assertThat(assign(WORKER, body(task, "wrk-1"), null).status()).isEqualTo(403);
    assertThat(assign(OTHER_SUP, body(task, "wrk-1"), null).status()).isEqualTo(403);

    Res first = assign(SUPERVISOR, body(task, "wrk-1"), 0);
    assertThat(first.status()).isEqualTo(201);
    assertThat(first.body().path("assignmentVersion").asInt()).isEqualTo(1);
    assertThat(first.body().path("state").asString()).isEqualTo("ACTIVE");
    assertThat(first.body().path("assignedBy").asString()).isEqualTo("sup-1");

    // 같은 작업자 다시 → 변화 없음 200, 낡은 버전 → 409
    Res same = assign(SUPERVISOR, body(task, "wrk-1"), null);
    assertThat(same.status()).isEqualTo(200);
    assertThat(same.body().path("assignmentVersion").asInt()).isEqualTo(1);
    assertThat(assign(SUPERVISOR, body(task, "wrk-2"), 0).status()).isEqualTo(409);

    // 재배정(WK-05) → v2, 앞 배정 SUPERSEDED, 활성 1건
    Res second = assign(SUPERVISOR, body(task, "wrk-2"), 1);
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.body().path("assignmentVersion").asInt()).isEqualTo(2);
    JsonNode active = get(WORKER, "/assignments?mfcId=" + MFC + "&taskId=" + task).body();
    assertThat(active.path("items")).hasSize(1);
    assertThat(active.path("items").path(0).path("workerId").asString()).isEqualTo("wrk-2");
    JsonNode old =
        get(
                TestJwt.service("mfc-fulfillment-service"),
                "/assignments?mfcId=" + MFC + "&taskId=" + task + "&state=SUPERSEDED")
            .body();
    assertThat(old.path("items").path(0).path("workerId").asString()).isEqualTo("wrk-1");
    assertThat(get(OTHER_SUP, "/assignments?mfcId=" + MFC).status()).isEqualTo(403);

    // 사건: 바뀐 두 번만(같은 작업자 재지정은 없음)
    List<String> payloads =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'AssignmentPublished'"
                + " AND aggregateid = ? ORDER BY created_at",
            String.class,
            "MANUAL:" + task);
    assertThat(payloads).hasSize(2);
    assertThat(payloads.get(1)).contains("\"wrk-2\"").doesNotContain("wrk-1");
  }
}
