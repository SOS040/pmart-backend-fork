package com.pmart.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 약관 동의(CU-1, 2026-11-17): 본인 조회·동의·마케팅 켜고 끄기·필수 철회 422·버전 422·멱등 재시도, 약관 버전이 바뀌면 다시 받아야 함(구매 가능
 * 여부), auth 가 부르는 내부 경로(가입 전 계정·로그인 중 동의). 현행 버전은 설정 사본(customer-service.yml)의 값. 구매 차단 스위치는 켠
 * 경로(설정 사본은 false — 꺼진 경로는 CustomerAddressFlowIT). 실제 PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.customer.consents.purchase-gate=true"
    })
@Import(TestJwtConfiguration.class)
class ConsentFlowIT extends PostgresKafkaContainerSupport {

  static final String TERMS = "2026-11-17";

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

  Res get(String token, String path) {
    var e =
        http.get()
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  } else {
                    h.set("X-Source-System", "auth-service");
                  }
                })
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res post(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", token != null ? "customer-app" : "auth-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> item(String type, String version, boolean agreed) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("consentType", type);
    m.put("version", version);
    m.put("agreed", agreed);
    return m;
  }

  @SafeVarargs
  static Map<String, Object> changes(Map<String, Object>... items) {
    return Map.of("changes", new ArrayList<>(List.of(items)));
  }

  static Map<String, Object> allRequired(boolean marketing) {
    return changes(
        item("TERMS_OF_SERVICE", TERMS, true),
        item("PRIVACY_POLICY", TERMS, true),
        item("AGE_OVER_14", "1", true),
        item("MARKETING", TERMS, marketing));
  }

  static JsonNode find(JsonNode items, String type) {
    for (JsonNode i : items) {
      if (type.equals(i.path("consentType").asString())) {
        return i;
      }
    }
    throw new AssertionError(type + " 없음");
  }

  int events(String accountId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = 'CustomerConsentChanged'"
                + " AND topic = 'commerce.customer.v1' AND aggregateid = ?",
            Integer.class,
            accountId);
    return n == null ? 0 : n;
  }

  static String newCustomer() {
    return "CU-CF-" + UUID.randomUUID();
  }

  @Test
  void 처음에는_모두_비어_있고_동의하면_구매_가능해진다() {
    String cu = newCustomer();
    String token = TestJwt.customer(cu);

    Res before = get(token, "/me/consents");
    assertThat(before.status()).isEqualTo(200);
    assertThat(before.body().path("requiredConsentOutdated").asBoolean()).isTrue();
    assertThat(before.body().path("items")).hasSize(4);
    JsonNode terms = find(before.body().path("items"), "TERMS_OF_SERVICE");
    assertThat(terms.path("required").asBoolean()).isTrue();
    assertThat(terms.path("agreed").asBoolean()).isFalse();
    assertThat(terms.path("latestVersion").asString()).isEqualTo(TERMS);
    assertThat(find(before.body().path("items"), "AGE_OVER_14").path("latestVersion").asString())
        .isEqualTo("1");
    assertThat(find(before.body().path("items"), "MARKETING").path("required").asBoolean())
        .isFalse();
    // 구매 가능 여부(order 가 보는 값)도 필수 동의를 본다(계정 행은 /me 가 만든다)
    assertThat(get(token, "/me").status()).isEqualTo(200);
    JsonNode status0 = get(null, "/internal/accounts/" + cu + "/status").body();
    assertThat(status0.path("purchasable").asBoolean()).isFalse();
    assertThat(status0.path("requiredConsentOutdated").asBoolean()).isTrue();

    String key = UUID.randomUUID().toString();
    Res applied = post(token, "/me/consent-changes", key, allRequired(true));
    assertThat(applied.status()).isEqualTo(200);
    assertThat(applied.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(applied.body().path("requestId").asString()).isEqualTo(key);

    JsonNode after = get(token, "/me/consents").body();
    assertThat(after.path("requiredConsentOutdated").asBoolean()).isFalse();
    JsonNode t = find(after.path("items"), "TERMS_OF_SERVICE");
    assertThat(t.path("agreed").asBoolean()).isTrue();
    assertThat(t.path("version").asString()).isEqualTo(TERMS);
    assertThat(t.path("channel").asString()).isEqualTo("APP");
    assertThat(t.path("agreedAt").asString()).isNotBlank();
    assertThat(find(after.path("items"), "MARKETING").path("agreed").asBoolean()).isTrue();
    assertThat(events(cu)).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_consent_history WHERE account_id = ?",
                Integer.class,
                cu))
        .isEqualTo(4);
    JsonNode status1 = get(null, "/internal/accounts/" + cu + "/status").body();
    assertThat(status1.path("purchasable").asBoolean()).isTrue();
    assertThat(status1.path("requiredConsentOutdated").asBoolean()).isFalse();

    // 같은 키 재시도 = 처음 결과(다시 반영하지 않음), 같은 키 다른 본문 = 409
    Res retry = post(token, "/me/consent-changes", key, allRequired(true));
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.body().path("requestId").asString()).isEqualTo(key);
    assertThat(events(cu)).isEqualTo(4);
    assertThat(post(token, "/me/consent-changes", key, allRequired(false)).status()).isEqualTo(409);
    // 결과 조회(본인만)
    assertThat(get(token, "/me/consent-changes/" + key).body().path("status").asString())
        .isEqualTo("APPLIED");
    assertThat(get(TestJwt.customer(newCustomer()), "/me/consent-changes/" + key).status())
        .isEqualTo(404);
  }

  @Test
  void 마케팅은_켜고_끌_수_있고_필수는_끌_수_없다() {
    String cu = newCustomer();
    String token = TestJwt.customer(cu);
    assertThat(
            post(token, "/me/consent-changes", UUID.randomUUID().toString(), allRequired(true))
                .status())
        .isEqualTo(200);
    int base = events(cu);

    // 마케팅 끄기 → 철회 시각, 사건 1건
    assertThat(
            post(
                    token,
                    "/me/consent-changes",
                    UUID.randomUUID().toString(),
                    changes(item("MARKETING", TERMS, false)))
                .status())
        .isEqualTo(200);
    JsonNode m = find(get(token, "/me/consents").body().path("items"), "MARKETING");
    assertThat(m.path("agreed").asBoolean()).isFalse();
    assertThat(m.path("withdrawnAt").asString()).isNotBlank();
    assertThat(events(cu)).isEqualTo(base + 1);
    JsonNode payload =
        mapper
            .readTree(
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE type = 'CustomerConsentChanged'"
                        + " AND aggregateid = ? ORDER BY created_at DESC LIMIT 1",
                    String.class,
                    cu))
            .path("payload");
    assertThat(payload.path("consentType").asString()).isEqualTo("MARKETING");
    assertThat(payload.path("granted").asBoolean()).isFalse();
    assertThat(payload.path("termsVersion").asString()).isEqualTo(TERMS);
    assertThat(payload.path("version").asInt()).isEqualTo(2);
    assertThat(payload.path("channel").asString()).isEqualTo("APP");

    // 같은 값이면 바뀌지 않는다(사건 없음)
    assertThat(
            post(
                    token,
                    "/me/consent-changes",
                    UUID.randomUUID().toString(),
                    changes(item("MARKETING", TERMS, false)))
                .status())
        .isEqualTo(200);
    assertThat(events(cu)).isEqualTo(base + 1);

    // 다시 켜기 → 철회 시각 지움
    post(
        token,
        "/me/consent-changes",
        UUID.randomUUID().toString(),
        changes(item("MARKETING", TERMS, true)));
    JsonNode on = find(get(token, "/me/consents").body().path("items"), "MARKETING");
    assertThat(on.path("agreed").asBoolean()).isTrue();
    assertThat(on.path("withdrawnAt").isMissingNode() || on.path("withdrawnAt").isNull()).isTrue();

    // 필수 철회 = 탈퇴(범위 밖) → 422, 그대로
    Res withdraw =
        post(
            token,
            "/me/consent-changes",
            UUID.randomUUID().toString(),
            changes(item("PRIVACY_POLICY", TERMS, false)));
    assertThat(withdraw.status()).isEqualTo(422);
    assertThat(withdraw.body().path("code").asString())
        .isEqualTo("REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN");
    assertThat(
            find(get(token, "/me/consents").body().path("items"), "PRIVACY_POLICY")
                .path("agreed")
                .asBoolean())
        .isTrue();

    // 현행이 아닌 버전 → 422, 같은 항목 두 번 → 400
    Res old =
        post(
            token,
            "/me/consent-changes",
            UUID.randomUUID().toString(),
            changes(item("MARKETING", "2026-01-01", true)));
    assertThat(old.status()).isEqualTo(422);
    assertThat(old.body().path("code").asString()).isEqualTo("CONSENT_VERSION_OUTDATED");
    assertThat(
            post(
                    token,
                    "/me/consent-changes",
                    UUID.randomUUID().toString(),
                    changes(item("MARKETING", TERMS, true), item("MARKETING", TERMS, false)))
                .status())
        .isEqualTo(400);
    // 없앤 항목(0.3.0 에서 MARKETING 하나로)은 400
    assertThat(
            post(
                    token,
                    "/me/consent-changes",
                    UUID.randomUUID().toString(),
                    changes(item("MARKETING_PUSH", TERMS, true)))
                .status())
        .isEqualTo(400);
    // 운영자 토큰은 403
    assertThat(get(TestJwt.operator("OP-1", List.of(), List.of()), "/me/consents").status())
        .isEqualTo(403);
  }

  @Test
  void 약관_버전이_바뀌면_필수_항목을_다시_받아야_한다() {
    String cu = newCustomer();
    String token = TestJwt.customer(cu);
    post(token, "/me/consent-changes", UUID.randomUUID().toString(), allRequired(false));
    assertThat(get(token, "/me/consents").body().path("requiredConsentOutdated").asBoolean())
        .isFalse();

    // 이 고객이 이용약관 옛 버전에 동의한 상태(= 설정의 현행 버전이 바뀐 것과 같다)
    jdbc.update(
        "UPDATE customer_consent SET terms_version = '2026-01-01'"
            + " WHERE account_id = ? AND consent_type = 'TERMS_OF_SERVICE'",
        cu);
    JsonNode outdated = get(token, "/me/consents").body();
    assertThat(outdated.path("requiredConsentOutdated").asBoolean()).isTrue();
    JsonNode t = find(outdated.path("items"), "TERMS_OF_SERVICE");
    assertThat(t.path("version").asString()).isEqualTo("2026-01-01");
    assertThat(t.path("latestVersion").asString()).isEqualTo(TERMS);
    JsonNode internal = get(null, "/internal/accounts/" + cu + "/consents").body();
    assertThat(internal.path("missingRequired").get(0).asString())
        .isEqualTo("TERMS_OF_SERVICE:" + TERMS);
    assertThat(internal.path("undecidedOptional")).isEmpty(); // 마케팅 거부는 현행 버전의 결정
    assertThat(
            get(null, "/internal/accounts/" + cu + "/status")
                .body()
                .path("purchasable")
                .asBoolean())
        .isFalse();

    // 현행 버전으로 다시 동의 → 구매 가능
    post(
        token,
        "/me/consent-changes",
        UUID.randomUUID().toString(),
        changes(item("TERMS_OF_SERVICE", TERMS, true)));
    assertThat(
            get(null, "/internal/accounts/" + cu + "/status")
                .body()
                .path("purchasable")
                .asBoolean())
        .isTrue();
  }

  @Test
  void auth_내부_경로는_가입_전_계정도_답하고_로그인_중_동의를_반영한다() {
    String cu = newCustomer();

    // customer 행이 아직 없는 가입 중 계정: 404 가 아니라 모두 빠짐
    Res none = get(null, "/internal/accounts/" + cu + "/consents");
    assertThat(none.status()).isEqualTo(200);
    assertThat(none.body().path("requiredConsentOutdated").asBoolean()).isTrue();
    assertThat(none.body().path("missingRequired")).hasSize(3);
    assertThat(none.body().path("undecidedOptional").get(0).asString())
        .isEqualTo("MARKETING:" + TERMS);

    Map<String, Object> body = new LinkedHashMap<>(allRequired(false));
    body.put("channel", "SIGNUP");
    String key = UUID.randomUUID().toString();
    Res applied = post(null, "/internal/accounts/" + cu + "/consent-changes", key, body);
    assertThat(applied.status()).isEqualTo(200);
    assertThat(applied.body().path("requiredConsentOutdated").asBoolean()).isFalse();
    assertThat(applied.body().path("missingRequired")).isEmpty();
    assertThat(applied.body().path("undecidedOptional")).isEmpty();
    assertThat(find(applied.body().path("items"), "PRIVACY_POLICY").path("channel").asString())
        .isEqualTo("SIGNUP");
    // 계정 행도 생겼다(프로필은 CustomerAuthenticated 가 채운다)
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM customer WHERE account_id = ?", String.class, cu))
        .isEqualTo("ACTIVE");
    // 같은 키 재전송(auth 의 재시도)은 다시 반영하지 않고 지금 상태를 준다
    assertThat(post(null, "/internal/accounts/" + cu + "/consent-changes", key, body).status())
        .isEqualTo(200);
    assertThat(events(cu)).isEqualTo(4);

    // 필수 항목을 빼고 보내면 상태가 빠진 채로(auth 가 REQUIRED_CONSENT_MISSING 으로 바꾼다), 끄면 422
    String other = newCustomer();
    Map<String, Object> partial =
        new LinkedHashMap<>(changes(item("TERMS_OF_SERVICE", TERMS, true)));
    partial.put("channel", "SIGNUP");
    JsonNode partialStatus =
        post(
                null,
                "/internal/accounts/" + other + "/consent-changes",
                UUID.randomUUID().toString(),
                partial)
            .body();
    assertThat(partialStatus.path("missingRequired")).hasSize(2);
    Map<String, Object> off = new LinkedHashMap<>(changes(item("AGE_OVER_14", "1", false)));
    off.put("channel", "LOGIN");
    assertThat(
            post(
                    null,
                    "/internal/accounts/" + other + "/consent-changes",
                    UUID.randomUUID().toString(),
                    off)
                .status())
        .isEqualTo(422);
    // 고객 토큰으로 내부 경로는 403
    assertThat(get(TestJwt.customer(cu), "/internal/accounts/" + cu + "/consents").status())
        .isEqualTo(403);
  }
}
