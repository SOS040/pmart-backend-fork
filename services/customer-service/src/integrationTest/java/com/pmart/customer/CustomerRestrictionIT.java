package com.pmart.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 계정 제한(0.16.0, CU-7 — 사용자: 이용제한 = 문의·리뷰 작성, 정지 = 주문·로그인까지, 7일 자동 해제, 영구 정지 없음): 직원 걸기·풀기(사유 필수,
 * 이력, 권한), 정지면 구매 불가, review-author·/me 에 제한, case 정지 단어 사건 → 자동 7일 정지(재전달 한 번, 늦은 사건 버림), 기한 지나면
 * NONE, CustomerRestricted 사건.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.customer.consents.purchase-gate=false", // 동의와 상관없이 제한만 본다
      // case 구독 — 정본 customer-service.yml 에 넣기 전에도 이 시험이 돌게(배포 때 정본·사본에 같이 넣는다)
      "spring.cloud.function.definition=authEvents;orderEvents;caseEvents",
      "spring.cloud.stream.bindings.caseEvents-in-0.destination=platform.case.v1",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class CustomerRestrictionIT extends PostgresKafkaContainerSupport {

  static final String CS = TestJwt.operator("cs-r1", List.of("CS_AGENT"), List.of());
  static final String MFC = TestJwt.operator("mfc-r1", List.of("MFC_WORKER"), List.of("MFC-1"));
  static final String SVC = TestJwt.service("order-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

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

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res post(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  String customer() {
    String id = "CU-RS-" + UUID.randomUUID();
    assertThat(get(TestJwt.customer(id), "/me").status()).isEqualTo(200);
    return id;
  }

  int events(String accountId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = 'CustomerRestricted'"
                + " AND topic = 'commerce.customer.v1' AND aggregateid = ?",
            Integer.class,
            accountId);
    return n == null ? 0 : n;
  }

  @Test
  void staffRestrictsSuspendsAndReleases() {
    String a = customer();
    String path = "/customers/" + a + "/restrictions";

    // 권한·사유
    assertThat(
            post(MFC, path, "k0", Map.of("restriction", "RESTRICTED", "reason", "욕설 사용")).status())
        .isEqualTo(403);
    assertThat(post(CS, path, "k1", Map.of("restriction", "RESTRICTED", "reason", " ")).status())
        .isEqualTo(400);

    // 이용제한 — 7일, 구매는 됨, 리뷰 작성자 조회·/me 에 보임
    Res r = post(CS, path, "k2", Map.of("restriction", "RESTRICTED", "reason", "욕설 사용"));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("status").asString()).isEqualTo("APPLIED");
    JsonNode d = get(CS, "/customers/" + a).body();
    assertThat(d.path("restriction").asString()).isEqualTo("RESTRICTED");
    Instant until = Instant.parse(d.path("restrictedUntil").asString());
    assertThat(Duration.between(Instant.now(), until).toHours()).isBetween(166L, 168L);
    JsonNode st = get(SVC, "/internal/accounts/" + a + "/status").body();
    assertThat(st.path("restriction").asString()).isEqualTo("RESTRICTED");
    assertThat(st.path("purchasable").asBoolean()).isTrue();
    assertThat(
            get(SVC, "/internal/accounts/" + a + "/review-author")
                .body()
                .path("restriction")
                .asString())
        .isEqualTo("RESTRICTED");
    assertThat(get(TestJwt.customer(a), "/me").body().path("restriction").asString())
        .isEqualTo("RESTRICTED");

    // 같은 키 재전송은 같은 결과·사건 하나
    assertThat(
            post(CS, path, "k2", Map.of("restriction", "RESTRICTED", "reason", "욕설 사용"))
                .body()
                .path("resultId")
                .asString())
        .isEqualTo(r.body().path("resultId").asString());
    assertThat(events(a)).isEqualTo(1);

    // 정지 — 구매 불가
    assertThat(post(CS, path, "k3", Map.of("restriction", "SUSPENDED", "reason", "반복 욕설")).status())
        .isEqualTo(201);
    assertThat(
            get(SVC, "/internal/accounts/" + a + "/status").body().path("purchasable").asBoolean())
        .isFalse();

    // 풀기 — 다시 풀면 409
    String rel = "/customers/" + a + "/restriction-releases";
    assertThat(post(CS, rel, "k4", Map.of("reason", "오인식")).status()).isEqualTo(200);
    Res again = post(CS, rel, "k5", Map.of("reason", "오인식"));
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().path("code").asString()).isEqualTo("NOT_RESTRICTED");
    assertThat(
            get(SVC, "/internal/accounts/" + a + "/status").body().path("purchasable").asBoolean())
        .isTrue();

    // 이력(최근 먼저)·사건 셋
    JsonNode h = get(CS, path).body();
    assertThat(h.path("restriction").asString()).isEqualTo("NONE");
    assertThat(h.path("items")).hasSize(3);
    assertThat(h.path("items").path(0).path("restriction").asString()).isEqualTo("NONE");
    assertThat(h.path("items").path(0).path("previous").asString()).isEqualTo("SUSPENDED");
    assertThat(h.path("items").path(2).path("reason").asString()).isEqualTo("욕설 사용");
    assertThat(get(MFC, path).status()).isEqualTo(403);
    assertThat(events(a)).isEqualTo(3);
  }

  void profanity(UUID eventId, String accountId, Instant detectedAt) throws InterruptedException {
    String json =
        EventEnvelopeFixtures.envelope("CustomerProfanityDetected")
            .eventId(eventId)
            .from("case-service")
            .aggregate("Case", "CASE-" + eventId, 1)
            .payload(
                Map.of(
                    "customerId",
                    accountId,
                    "inquiryId",
                    "INQ-1",
                    "caseId",
                    "CASE-1",
                    "source",
                    "INQUIRY",
                    "detectedAt",
                    detectedAt.toString()))
            .buildJson();
    kafka.send("platform.case.v1", accountId, json).join();
    Instant end = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(end)) {
      Integer n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM inbox_event WHERE event_id = ?", Integer.class, eventId);
      if (n != null && n > 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("CustomerProfanityDetected 미반영 " + eventId);
  }

  @Test
  void profanityEventSuspendsForSevenDaysAndExpires() throws Exception {
    String a = customer();
    Instant detected =
        Instant.now().minus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    UUID ev = UUID.randomUUID();
    profanity(ev, a, detected);

    JsonNode st = get(SVC, "/internal/accounts/" + a + "/status").body();
    assertThat(st.path("restriction").asString()).isEqualTo("SUSPENDED");
    assertThat(st.path("purchasable").asBoolean()).isFalse();
    assertThat(Instant.parse(st.path("restrictedUntil").asString()))
        .isEqualTo(detected.plus(Duration.ofDays(7)));
    JsonNode h = get(CS, "/customers/" + a + "/restrictions").body();
    assertThat(h.path("items")).hasSize(1);
    assertThat(h.path("items").path(0).path("source").asString()).isEqualTo("AUTO_PROFANITY");
    assertThat(h.path("items").path(0).path("actor").asString()).isEqualTo("system:case-profanity");

    // 같은 정지가 이미 있으면(같은 기한) 또 걸지 않는다, 8일 전 늦은 사건은 버린다
    profanity(UUID.randomUUID(), a, detected);
    String b = customer();
    profanity(UUID.randomUUID(), b, Instant.now().minus(Duration.ofDays(8)));
    assertThat(get(CS, "/customers/" + a + "/restrictions").body().path("items")).hasSize(1);
    assertThat(get(CS, "/customers/" + b).body().path("restriction").asString()).isEqualTo("NONE");
    assertThat(events(a)).isEqualTo(1);

    // 기한 지나면 NONE(자동 해제) — 구매 다시 됨
    jdbc.update(
        "UPDATE customer SET restriction_until = now() - interval '1 minute' WHERE account_id = ?",
        a);
    JsonNode after = get(SVC, "/internal/accounts/" + a + "/status").body();
    assertThat(after.path("restriction").asString()).isEqualTo("NONE");
    assertThat(after.path("restrictedUntil").isNull()).isTrue();
    assertThat(after.path("purchasable").asBoolean()).isTrue();
  }
}
