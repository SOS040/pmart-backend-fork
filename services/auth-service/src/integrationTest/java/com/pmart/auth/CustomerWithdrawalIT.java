package com.pmart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.auth.application.WithdrawnIdentifierPurger;
import com.pmart.auth.domain.WithdrawnIdentifierRules;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * 고객 탈퇴 auth 몫(2026-11-20 ADR-0077, 계약 0.9.0): 재인증 확인(200·401·403)과 CustomerWithdrawalCompleted
 * 반영(세션 끊기·계정 WITHDRAWN·소셜 연결 지움·격리 표에 해시·같은 소셜 재로그인은 새 계정·재전달 멱등), 만료 파기. 실제 PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.auth.customer.require-identity=false",
      "pmart.auth.customer.require-consent=false",
      "pmart.auth.customer.google.client-ids=web-client.apps.googleusercontent.com",
      "pmart.auth.customer.kakao.client-ids=kakao-native-app-key",
      "pmart.auth.customer.naver.client-ids=naver-client-id"
    })
@Import(CustomerSocialLoginIT.FakeGoogle.class)
class CustomerWithdrawalIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired WithdrawnIdentifierPurger purger;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res post(String path, Map<String, Object> body, Map<String, String> headers) {
    var e =
        http.post()
            .uri(path)
            .headers(h -> headers.forEach(h::set))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res login(String device, String token) {
    return post(
        "/auth/social-logins",
        Map.of("provider", "GOOGLE", "providerToken", token),
        Map.of(
            "Idempotency-Key",
            UUID.randomUUID().toString(),
            "X-Source-System",
            "customer-app",
            "X-Device-Id",
            device));
  }

  Res reauth(String accountId, String provider, String token) {
    return post(
        "/internal/customers/" + accountId + "/reauthentications",
        Map.of("provider", provider, "providerToken", token),
        Map.of("X-Source-System", "customer-service"));
  }

  Res refresh(String device, String refreshToken) {
    return post(
        "/auth/tokens",
        Map.of("refreshToken", refreshToken),
        Map.of(
            "Idempotency-Key",
            UUID.randomUUID().toString(),
            "X-Source-System",
            "customer-app",
            "X-Device-Id",
            device));
  }

  /** 0.12.0 CU-6: 마지막 로그인·토큰 갱신(요청 순서·중복 한 번·없는 계정 null), 개수 검사 400. */
  @Test
  void customerActivitiesGiveLastLoginAndRefresh() {
    String device = "phone-" + UUID.randomUUID();
    Res first = login(device, CustomerSocialLoginIT.google("g-" + UUID.randomUUID()));
    assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
    String accountId = first.body().path("accountId").asString();
    String path = "/internal/customers/activities?accountIds=";

    JsonNode before = get(path + accountId + ",CU-NONE," + accountId).body().path("items");
    assertThat(before.findValuesAsString("accountId")).containsExactly(accountId, "CU-NONE");
    assertThat(before.path(0).path("lastLoginAt").asString()).isNotEmpty();
    assertThat(before.path(0).path("lastTokenRefreshAt").isNull()).isTrue();
    assertThat(before.path(1).path("lastLoginAt").isNull()).isTrue();

    Res refreshed = refresh(device, first.body().path("tokens").path("refreshToken").asString());
    assertThat(refreshed.status()).as(refreshed.body().toString()).isEqualTo(200);
    JsonNode after = get(path + accountId).body().path("items").path(0);
    assertThat(Instant.parse(after.path("lastTokenRefreshAt").asString()))
        .isAfterOrEqualTo(Instant.parse(after.path("lastLoginAt").asString()));

    assertThat(get(path + ",").status()).isEqualTo(400);
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 201; i++) {
      many.append("CU-").append(i).append(',');
    }
    assertThat(get(path + many).status()).isEqualTo(400);
  }

  Res get(String path) {
    var e = http.get().uri(path).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  @Test
  void reauthenticationChecksTokenAndLink() {
    String sub = "g-" + UUID.randomUUID();
    Res first = login("phone-" + UUID.randomUUID(), CustomerSocialLoginIT.google(sub));
    assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
    String accountId = first.body().path("accountId").asString();
    int sessionsBefore = sessions(accountId);

    // 같은 소셜 계정 → 200, 상태 변경 없음(세션 그대로)
    Res ok = reauth(accountId, "GOOGLE", CustomerSocialLoginIT.google(sub));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("verified").asBoolean()).isTrue();
    assertThat(ok.body().path("provider").asString()).isEqualTo("GOOGLE");
    assertThat(sessions(accountId)).isEqualTo(sessionsBefore);

    // 토큰 거절(aud 다름) → 401 PROVIDER_REJECTED
    Res rejected =
        reauth(
            accountId,
            "GOOGLE",
            CustomerSocialLoginIT.idToken(sub, "other-client", "https://accounts.google.com"));
    assertThat(rejected.status()).isEqualTo(401);
    assertThat(rejected.body().path("code").asString()).isEqualTo("PROVIDER_REJECTED");

    // 연결 없는 소셜 계정 → 403
    Res unknown =
        reauth(accountId, "GOOGLE", CustomerSocialLoginIT.google("g-" + UUID.randomUUID()));
    assertThat(unknown.status()).isEqualTo(403);
    assertThat(unknown.body().path("code").asString()).isEqualTo("REAUTHENTICATION_MISMATCH");

    // 다른 계정에 연결된 소셜 계정 → 403
    String otherSub = "g-" + UUID.randomUUID();
    assertThat(login("phone-" + UUID.randomUUID(), CustomerSocialLoginIT.google(otherSub)).status())
        .isEqualTo(200);
    Res other = reauth(accountId, "GOOGLE", CustomerSocialLoginIT.google(otherSub));
    assertThat(other.status()).isEqualTo(403);
    assertThat(other.body().path("code").asString()).isEqualTo("REAUTHENTICATION_MISMATCH");
  }

  @Test
  void withdrawalCompletedEndsAccountAndIsolatesIdentifier() throws Exception {
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    Res first = login(device, CustomerSocialLoginIT.google(sub));
    assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
    String accountId = first.body().path("accountId").asString();
    String refreshToken = first.body().path("tokens").path("refreshToken").asString();

    Instant completedAt = Instant.parse("2026-11-20T03:00:00Z");
    UUID eventId = UUID.randomUUID();
    withdrawalCompleted(eventId, accountId, completedAt);
    awaitInbox(eventId);

    // 계정 사용 안 함·세션 끊김·소셜 연결 없음
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM customer_account WHERE account_id = ?", String.class, accountId))
        .isEqualTo("WITHDRAWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE principal_id = ? AND state = 'ACTIVE'",
                Integer.class,
                accountId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM social_identity WHERE account_id = ?",
                Integer.class,
                accountId))
        .isZero();
    assertThat(refresh(device, refreshToken).status()).isEqualTo(401);
    // 이미 나간 access token 은 게이트웨이 차단(SubjectBlocked, outbox)
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'SubjectBlocked' AND aggregateid = ?"
                    + " AND payload::text LIKE '%ACCOUNT_DISABLED%'",
                Integer.class, accountId))
        .isEqualTo(1);

    // 격리 표: 해시·제공자·완료 + 5년, 원래 sub 는 없다
    String hash = WithdrawnIdentifierRules.hash("GOOGLE", sub);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT * FROM withdrawn_social_identifier WHERE identifier_hash = ?", hash);
    assertThat(row.get("provider")).isEqualTo("GOOGLE");
    assertThat(((Timestamp) row.get("withdrawn_at")).toInstant()).isEqualTo(completedAt);
    assertThat(((Timestamp) row.get("expires_at")).toInstant())
        .isEqualTo(Instant.parse("2031-11-20T03:00:00Z"));
    assertThat(row).doesNotContainKey("account_id");

    // 재전달(같은 eventId)·다른 eventId 의 같은 사건도 아무것도 바꾸지 않는다(SubjectBlocked 한 번)
    withdrawalCompleted(eventId, accountId, completedAt);
    UUID again = UUID.randomUUID();
    withdrawalCompleted(again, accountId, completedAt.plusSeconds(60));
    awaitInbox(again);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'SubjectBlocked' AND aggregateid = ?",
                Integer.class,
                accountId))
        .isEqualTo(1);
    assertThat(
            ((Timestamp)
                    jdbc.queryForObject(
                        "SELECT withdrawn_at FROM withdrawn_social_identifier WHERE identifier_hash = ?",
                        Timestamp.class,
                        hash))
                .toInstant())
        .isEqualTo(completedAt);

    // 같은 Google 계정으로 다시 로그인 → 새 계정
    Res rejoin = login("phone-" + UUID.randomUUID(), CustomerSocialLoginIT.google(sub));
    assertThat(rejoin.status()).as(rejoin.body().toString()).isEqualTo(200);
    assertThat(rejoin.body().path("newAccount").asBoolean()).isTrue();
    assertThat(rejoin.body().path("accountId").asString())
        .isNotEqualTo(accountId)
        .startsWith("CU-");
    // 탈퇴한 accountId 로는 재인증도 안 된다
    assertThat(reauth(accountId, "GOOGLE", CustomerSocialLoginIT.google(sub)).status())
        .isEqualTo(403);
  }

  /**
   * CU-7(2026-11-23): customer CustomerRestricted SUSPENDED → 세션 끝·SubjectBlocked·기한 안 로그인 403
   * ACCOUNT_SUSPENDED, 옛 차수 무시, 풀기(NONE) 뒤 로그인 됨. 이용제한(RESTRICTED)은 로그인을 막지 않는다.
   */
  @Test
  void suspensionBlocksLoginAndRefreshUntilReleased() throws Exception {
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    Res first = login(device, CustomerSocialLoginIT.google(sub));
    assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
    String accountId = first.body().path("accountId").asString();
    String refreshToken = first.body().path("tokens").path("refreshToken").asString();

    Instant until = Instant.now().plus(Duration.ofDays(7));
    UUID ev = UUID.randomUUID();
    restricted(ev, accountId, "SUSPENDED", until, 2);
    awaitInbox(ev);
    assertThat(refresh(device, refreshToken).status()).isEqualTo(401);
    Res blocked = login("phone-" + UUID.randomUUID(), CustomerSocialLoginIT.google(sub));
    assertThat(blocked.status()).isEqualTo(403);
    assertThat(blocked.body().path("code").asString()).isEqualTo("ACCOUNT_SUSPENDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'SubjectBlocked' AND aggregateid = ?",
                Integer.class,
                accountId))
        .isEqualTo(1);

    // 늦게 온 옛 차수(1) 풀기는 무시 — 여전히 막힘
    UUID old = UUID.randomUUID();
    restricted(old, accountId, "NONE", null, 1);
    awaitInbox(old);
    assertThat(login("phone-" + UUID.randomUUID(), CustomerSocialLoginIT.google(sub)).status())
        .isEqualTo(403);

    // 이용제한(차수 3)은 로그인 막지 않음
    UUID rs = UUID.randomUUID();
    restricted(rs, accountId, "RESTRICTED", until, 3);
    awaitInbox(rs);
    assertThat(login("phone-" + UUID.randomUUID(), CustomerSocialLoginIT.google(sub)).status())
        .isEqualTo(200);
  }

  void restricted(UUID eventId, String accountId, String restriction, Instant until, long version) {
    java.util.Map<String, Object> p = new java.util.HashMap<>();
    p.put("accountId", accountId);
    p.put("restriction", restriction);
    p.put("previous", "NONE");
    p.put("from", Instant.now().toString());
    p.put("until", until == null ? null : until.toString());
    p.put("source", "STAFF");
    String json =
        EventEnvelopeFixtures.envelope("CustomerRestricted")
            .eventId(eventId)
            .from("customer-service")
            .aggregate("CustomerRestriction", accountId, version)
            .payload(p)
            .buildJson();
    kafka.send("commerce.customer.v1", accountId, json).join();
  }

  @Test
  void purgeDeletesOnlyExpiredRows() {
    String expired = WithdrawnIdentifierRules.hash("KAKAO", "k-" + UUID.randomUUID());
    String kept = WithdrawnIdentifierRules.hash("KAKAO", "k-" + UUID.randomUUID());
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO withdrawn_social_identifier VALUES (?, 'KAKAO', ?, ?)",
        expired,
        Timestamp.from(now.minus(Duration.ofDays(5 * 366))),
        Timestamp.from(now.minusSeconds(60)));
    jdbc.update(
        "INSERT INTO withdrawn_social_identifier VALUES (?, 'KAKAO', ?, ?)",
        kept,
        Timestamp.from(now),
        Timestamp.from(WithdrawnIdentifierRules.expiresAt(now)));
    assertThat(purger.purge()).isGreaterThanOrEqualTo(1);
    assertThat(count(expired)).isZero();
    assertThat(count(kept)).isEqualTo(1);
  }

  int count(String hash) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM withdrawn_social_identifier WHERE identifier_hash = ?",
            Integer.class,
            hash);
    return n == null ? 0 : n;
  }

  int sessions(String accountId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_session WHERE principal_id = ? AND state = 'ACTIVE'",
            Integer.class,
            accountId);
    return n == null ? 0 : n;
  }

  void withdrawalCompleted(UUID eventId, String accountId, Instant completedAt) {
    String json =
        EventEnvelopeFixtures.envelope("CustomerWithdrawalCompleted")
            .eventId(eventId)
            .from("customer-service")
            .aggregate("Customer", accountId, 3)
            .payload(Map.of("accountId", accountId, "completedAt", completedAt.toString()))
            .buildJson();
    kafka.send("commerce.customer.v1", accountId, json).join();
  }

  void awaitInbox(UUID eventId) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM inbox_event WHERE event_id = ?", Integer.class, eventId);
      if (n != null && n > 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("CustomerWithdrawalCompleted 미반영 " + eventId);
  }
}
