package com.pmart.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
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
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 필수 약관 동의 단계(CU-1, auth 0.2.0 — require-consent=true 를 시험 속성으로 켠다 — 설정 사본은 false): 신규 가입 →
 * AUTH_CONSENT(토큰·프로필 사건 없음) → completions(같은 Google 계정의 ID 토큰 + 동의) → customer 에 넘김 →
 * AUTH_MEMBER·토큰·CustomerAuthenticated. 약관 버전이 바뀐 기존 회원도 다시 묻는다. 동의를 마치지 않은 가입은 다음 로그인도 가입.
 * customer-service 는 WireMock(내부 경로), 그 밖은 실제 PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.messaging.outbox.delete-after-insert=false",
      // 설정 사본은 require-identity true(LAN 2026-11-20). 휴대폰 확인 단계는 CustomerIdentityLoginIT 가 본다
      "pmart.auth.customer.require-identity=false",
      "pmart.auth.customer.google.client-ids=web-client.apps.googleusercontent.com",
      // 설정 사본은 false(LAN — 고객 앱 반영 전). 켠 경로를 시험한다
      "pmart.auth.customer.require-consent=true"
    })
@Import(CustomerSocialLoginIT.FakeGoogle.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "customer",
        baseUrlProperties = "pmart.auth.customer.customer-service-base-url"))
class CustomerConsentLoginIT extends PostgresKafkaContainerSupport {

  static final String CONSENTS = "/api/v1/internal/accounts/[^/]+/consents";
  static final String CHANGES = "/api/v1/internal/accounts/[^/]+/consent-changes";
  static final List<String> REQUIRED =
      List.of("TERMS_OF_SERVICE:2026-11-17", "PRIVACY_POLICY:2026-11-17", "AGE_OVER_14:1");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("customer")
  WireMockServer customer;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    customer.resetAll();
    // 기본: 동의가 하나도 없다(가입 중 계정) — 필수 3 빠짐, 마케팅 아직 안 고름
    stubStatus(urlPathMatching(CONSENTS), REQUIRED, List.of("MARKETING:2026-11-17"));
    // 반영하면 다 찬다
    stubChange(urlPathMatching(CHANGES), 200, status(List.of(), List.of()));
  }

  static String status(List<String> missing, List<String> undecided) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("accountId", "x");
    m.put("requiredConsentOutdated", !missing.isEmpty());
    m.put("missingRequired", missing);
    m.put("undecidedOptional", undecided);
    m.put("items", List.of());
    return new ObjectMapper().writeValueAsString(m);
  }

  void stubStatus(
      com.github.tomakehurst.wiremock.matching.UrlPathPattern url,
      List<String> missing,
      List<String> undecided) {
    customer.stubFor(
        WireMock.get(url)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(status(missing, undecided))));
  }

  void stubChange(
      com.github.tomakehurst.wiremock.matching.UrlPathPattern url, int code, String body) {
    customer.stubFor(
        WireMock.post(url)
            .willReturn(
                aResponse()
                    .withStatus(code)
                    .withHeader(
                        "Content-Type",
                        code >= 400 ? "application/problem+json" : "application/json")
                    .withBody(body)));
  }

  record Res(int status, JsonNode body) {}

  Res login(String key, String device, String token) {
    var e =
        http.post()
            .uri("/auth/social-logins")
            .headers(
                h -> {
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                  h.set("X-Device-Id", device);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("provider", "GOOGLE", "providerToken", token))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res complete(String arId, String key, String device, String token, List<String> agreed) {
    List<Map<String, Object>> consents = new ArrayList<>();
    for (String item : agreed) {
      String[] p = item.split(":", 2);
      consents.add(Map.of("consentType", p[0], "version", p[1], "agreed", true));
    }
    consents.add(Map.of("consentType", "MARKETING", "version", "2026-11-17", "agreed", false));
    var e =
        http.post()
            .uri("/auth/social-logins/{id}/completions", arId)
            .headers(
                h -> {
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                  h.set("X-Device-Id", device);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("providerToken", token, "consents", consents))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String path, String device) {
    var e =
        http.get()
            .uri(path)
            .headers(h -> h.set("X-Device-Id", device))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  int authenticatedEvents(String accountId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = 'CustomerAuthenticated'"
                + " AND aggregateid = ?",
            Integer.class,
            accountId);
    return n == null ? 0 : n;
  }

  int sessions(String accountId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_session WHERE principal_id = ?", Integer.class, accountId);
    return n == null ? 0 : n;
  }

  static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    arr.forEach(n -> out.add(n.asString()));
    return out;
  }

  @Test
  void 신규_가입은_동의를_받은_뒤에야_회원이_되고_토큰을_받는다() {
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    String loginKey = UUID.randomUUID().toString();

    Res first = login(loginKey, device, CustomerSocialLoginIT.google(sub));
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().path("state").asString()).isEqualTo("AUTH_CONSENT");
    assertThat(first.body().path("nextAction").asString()).isEqualTo("COMPLETE_CONSENT");
    assertThat(first.body().path("newAccount").asBoolean()).isTrue();
    assertThat(first.body().path("tokens").path("accessToken").isMissingNode()).isTrue();
    assertThat(strings(first.body().path("requiredConsents"))).isEqualTo(REQUIRED);
    assertThat(strings(first.body().path("optionalConsents")))
        .containsExactly("MARKETING:2026-11-17");
    String accountId = first.body().path("accountId").asString();
    String arId = first.body().path("authRequestId").asString();
    // 동의 전에는 세션도, Google 프로필 사건도 없다
    assertThat(sessions(accountId)).isZero();
    assertThat(authenticatedEvents(accountId)).isZero();

    // 같은 키 재전송·결과 조회는 같은 동의 대기
    Res replay = login(loginKey, device, CustomerSocialLoginIT.google(sub));
    assertThat(replay.body().path("state").asString()).isEqualTo("AUTH_CONSENT");
    assertThat(strings(replay.body().path("requiredConsents"))).isEqualTo(REQUIRED);
    Res mine = get("/auth/social-logins/" + arId, device);
    assertThat(mine.body().path("nextAction").asString()).isEqualTo("COMPLETE_CONSENT");
    assertThat(strings(mine.body().path("optionalConsents")))
        .containsExactly("MARKETING:2026-11-17");

    // 다른 기기·다른 Google 계정·검증 안 되는 토큰은 거절
    String doneKey = UUID.randomUUID().toString();
    assertThat(
            complete(arId, doneKey, "other-device", CustomerSocialLoginIT.google(sub), REQUIRED)
                .status())
        .isEqualTo(403);
    assertThat(
            complete(
                    arId,
                    doneKey,
                    device,
                    CustomerSocialLoginIT.google("g-someone-else-" + UUID.randomUUID()),
                    REQUIRED)
                .status())
        .isEqualTo(403);
    Res bad = complete(arId, doneKey, device, "not-a-jwt", REQUIRED);
    assertThat(bad.status()).isEqualTo(401);
    assertThat(bad.body().path("code").asString()).isEqualTo("PROVIDER_REJECTED");
    customer.verify(0, postRequestedFor(urlPathMatching(CHANGES)));

    // 동의 완료 → 회원·토큰·프로필 사건
    Res done = complete(arId, doneKey, device, CustomerSocialLoginIT.google(sub), REQUIRED);
    assertThat(done.status()).isEqualTo(200);
    assertThat(done.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    assertThat(done.body().path("accountId").asString()).isEqualTo(accountId);
    assertThat(done.body().path("newAccount").asBoolean()).isTrue();
    assertThat(done.body().path("tokens").path("accessToken").asString()).isNotBlank();
    assertThat(done.body().path("requiredConsents").isEmpty()).isTrue();
    assertThat(sessions(accountId)).isEqualTo(1);
    assertThat(authenticatedEvents(accountId)).isEqualTo(1);
    JsonNode payload =
        mapper
            .readTree(
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE type = 'CustomerAuthenticated'"
                        + " AND aggregateid = ?",
                    String.class,
                    accountId))
            .path("payload");
    assertThat(payload.path("email").asString()).isEqualTo(sub + "@gmail.com");
    // customer 에 넘긴 내용: 가입(SIGNUP)·고객이 고른 값·완료 요청의 키
    customer.verify(
        1,
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/accounts/" + accountId + "/consent-changes"))
            .withHeader("Idempotency-Key", equalTo(doneKey))
            .withHeader("X-Source-System", equalTo("auth-service"))
            .withRequestBody(matchingJsonPath("$.channel", equalTo("SIGNUP")))
            .withRequestBody(
                matchingJsonPath("$.changes[0].consentType", equalTo("TERMS_OF_SERVICE")))
            .withRequestBody(matchingJsonPath("$.changes[3].agreed", equalTo("false"))));
    assertThat(get("/auth/social-logins/" + arId, device).body().path("state").asString())
        .isEqualTo("AUTH_MEMBER");
    // 로그인 전 기기 동의(2026-11-19 BR-59): 동의 판단에 기기를 싣고, 완료 뒤 그 기기 동의를 계정으로 옮기라고 부른다
    // (스텁이 없어 404 — 옮기기 실패는 로그인을 막지 않는다)
    customer.verify(
        getRequestedFor(urlPathMatching(CONSENTS)).withQueryParam("deviceId", equalTo(device)));
    customer.verify(
        1,
        postRequestedFor(
                urlPathEqualTo(
                    "/api/v1/internal/accounts/" + accountId + "/device-consent-migrations"))
            .withRequestBody(matchingJsonPath("$.deviceId", equalTo(device)))
            // customer 는 변경 요청에 Idempotency-Key 필수(2026-11-20 LAN 400)
            .withHeader(
                "Idempotency-Key",
                com.github.tomakehurst.wiremock.client.WireMock.matching("[0-9a-f-]{36}")));

    // 응답 유실 재시도(같은 키) = 새 토큰, customer 는 다시 부르지 않음, 사건도 한 번
    Res again = complete(arId, doneKey, device, CustomerSocialLoginIT.google(sub), REQUIRED);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("tokens").path("accessToken").asString()).isNotBlank();
    customer.verify(1, postRequestedFor(urlPathMatching(CHANGES)));
    assertThat(authenticatedEvents(accountId)).isEqualTo(1);
    // 다른 키로는 이미 끝난 요청을 다시 끝낼 수 없다
    assertThat(
            complete(
                    arId,
                    UUID.randomUUID().toString(),
                    device,
                    CustomerSocialLoginIT.google(sub),
                    REQUIRED)
                .status())
        .isEqualTo(409);
  }

  @Test
  void 필수_항목이_빠지면_422_이고_동의_화면에서_나간_가입은_다음에도_가입이다() {
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    Res first = login(UUID.randomUUID().toString(), device, CustomerSocialLoginIT.google(sub));
    String arId = first.body().path("authRequestId").asString();
    String accountId = first.body().path("accountId").asString();

    // customer 가 반영 뒤에도 필수가 빠졌다고 답함(앱이 일부만 보냄) → 422, 상세에 빠진 항목
    stubChange(urlPathMatching(CHANGES), 200, status(List.of("AGE_OVER_14:1"), List.of()));
    Res partial =
        complete(
            arId,
            UUID.randomUUID().toString(),
            device,
            CustomerSocialLoginIT.google(sub),
            REQUIRED.subList(0, 2));
    assertThat(partial.status()).isEqualTo(422);
    assertThat(partial.body().path("code").asString()).isEqualTo("REQUIRED_CONSENT_MISSING");
    assertThat(partial.body().path("details").get(0).path("field").asString())
        .isEqualTo("AGE_OVER_14:1");
    // 필수를 끈 경우(customer 422) 도 "빠짐"
    stubChange(
        urlPathMatching(CHANGES),
        422,
        "{\"code\":\"REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN\",\"detail\":\"x\",\"status\":422}");
    Res off =
        complete(
            arId,
            UUID.randomUUID().toString(),
            device,
            CustomerSocialLoginIT.google(sub),
            REQUIRED);
    assertThat(off.status()).isEqualTo(422);
    assertThat(off.body().path("code").asString()).isEqualTo("REQUIRED_CONSENT_MISSING");
    // 그 사이 약관 버전이 바뀜(customer 422) → 그대로
    stubChange(
        urlPathMatching(CHANGES),
        422,
        "{\"code\":\"CONSENT_VERSION_OUTDATED\",\"detail\":\"x\",\"status\":422}");
    Res outdated =
        complete(
            arId,
            UUID.randomUUID().toString(),
            device,
            CustomerSocialLoginIT.google(sub),
            REQUIRED);
    assertThat(outdated.status()).isEqualTo(422);
    assertThat(outdated.body().path("code").asString()).isEqualTo("CONSENT_VERSION_OUTDATED");
    assertThat(sessions(accountId)).isZero();

    // 고객이 동의 화면에서 나감 → 다시 로그인해도 가입(newAccount)이고 다시 묻는다
    Res back = login(UUID.randomUUID().toString(), device, CustomerSocialLoginIT.google(sub));
    assertThat(back.body().path("state").asString()).isEqualTo("AUTH_CONSENT");
    assertThat(back.body().path("accountId").asString()).isEqualTo(accountId);
    assertThat(back.body().path("newAccount").asBoolean()).isTrue();
  }

  /**
   * 가입을 끝내지 않고 나간 계정(회원이 된 적 없음)은 다음 로그인에서 동의부터 다시(2026-11-20 BR-72, 사용자 "(가)·1번") — 저장된 동의가 충족이어도
   * customer 에 fresh=true 로 물어 필수·선택 전부를 다시 받는다. 회원이 된 계정은 fresh 없이 그대로.
   */
  @Test
  void 가입을_끝내지_않은_계정은_다음_로그인에서_동의부터_다시() {
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    Res first = login(UUID.randomUUID().toString(), device, CustomerSocialLoginIT.google(sub));
    assertThat(first.body().path("state").asString()).isEqualTo("AUTH_CONSENT");
    String accountId = first.body().path("accountId").asString();
    // 지난번에 동의를 저장해 둔 것처럼 — fresh 없이 물으면 충족, fresh=true 면 전부 다시
    String path = "/api/v1/internal/accounts/" + accountId + "/consents";
    stubStatus(urlPathEqualTo(path), List.of(), List.of());
    customer.stubFor(
        WireMock.get(urlPathEqualTo(path))
            .withQueryParam("fresh", equalTo("true"))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(status(REQUIRED, List.of("MARKETING:2026-11-17")))));

    Res again = login(UUID.randomUUID().toString(), device, CustomerSocialLoginIT.google(sub));
    assertThat(again.body().path("state").asString()).isEqualTo("AUTH_CONSENT");
    assertThat(again.body().path("accountId").asString()).isEqualTo(accountId);
    assertThat(strings(again.body().path("requiredConsents"))).containsExactlyElementsOf(REQUIRED);
    assertThat(strings(again.body().path("optionalConsents")))
        .containsExactly("MARKETING:2026-11-17");
    customer.verify(
        WireMock.getRequestedFor(urlPathEqualTo(path)).withQueryParam("fresh", equalTo("true")));

    // 동의 완료로 회원이 되면 다음 로그인은 fresh 없이(이미 회원 — 저장된 동의로 판단)
    assertThat(
            complete(
                    again.body().path("authRequestId").asString(),
                    UUID.randomUUID().toString(),
                    device,
                    CustomerSocialLoginIT.google(sub),
                    REQUIRED)
                .body()
                .path("state")
                .asString())
        .isEqualTo("AUTH_MEMBER");
    customer.resetRequests();
    Res member = login(UUID.randomUUID().toString(), device, CustomerSocialLoginIT.google(sub));
    assertThat(member.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    customer.verify(
        0, WireMock.getRequestedFor(urlPathEqualTo(path)).withQueryParam("fresh", equalTo("true")));
  }

  @Test
  void 약관_버전이_바뀐_기존_회원은_다음_로그인에서_다시_동의한다() {
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();
    // 가입·동의 완료
    Res first = login(UUID.randomUUID().toString(), device, CustomerSocialLoginIT.google(sub));
    String accountId = first.body().path("accountId").asString();
    assertThat(
            complete(
                    first.body().path("authRequestId").asString(),
                    UUID.randomUUID().toString(),
                    device,
                    CustomerSocialLoginIT.google(sub),
                    REQUIRED)
                .body()
                .path("state")
                .asString())
        .isEqualTo("AUTH_MEMBER");

    // 동의가 현행이면 바로 회원
    stubStatus(
        urlPathEqualTo("/api/v1/internal/accounts/" + accountId + "/consents"),
        List.of(),
        List.of());
    Res member = login(UUID.randomUUID().toString(), device, CustomerSocialLoginIT.google(sub));
    assertThat(member.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    assertThat(member.body().path("newAccount").asBoolean()).isFalse();
    assertThat(member.body().path("tokens").path("accessToken").asString()).isNotBlank();

    // 이용약관 버전이 바뀜 → 기존 회원도 AUTH_CONSENT(가입 아님), 완료는 LOGIN 으로 기록
    stubStatus(
        urlPathEqualTo("/api/v1/internal/accounts/" + accountId + "/consents"),
        List.of("TERMS_OF_SERVICE:2026-12-01"),
        List.of());
    Res asked = login(UUID.randomUUID().toString(), device, CustomerSocialLoginIT.google(sub));
    assertThat(asked.body().path("state").asString()).isEqualTo("AUTH_CONSENT");
    assertThat(asked.body().path("newAccount").asBoolean()).isFalse();
    assertThat(strings(asked.body().path("requiredConsents")))
        .containsExactly("TERMS_OF_SERVICE:2026-12-01");
    assertThat(asked.body().path("optionalConsents")).isEmpty();
    Res done =
        complete(
            asked.body().path("authRequestId").asString(),
            UUID.randomUUID().toString(),
            device,
            CustomerSocialLoginIT.google(sub),
            List.of("TERMS_OF_SERVICE:2026-12-01"));
    assertThat(done.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    assertThat(done.body().path("newAccount").asBoolean()).isFalse();
    customer.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/accounts/" + accountId + "/consent-changes"))
            .withRequestBody(matchingJsonPath("$.channel", equalTo("LOGIN"))));
  }

  @Test
  void customer_service_가_안_되면_503_이고_실패로_세지_않으며_비회원_세션은_그대로다() {
    customer.resetAll();
    customer.stubFor(
        WireMock.get(urlPathMatching(CONSENTS)).willReturn(aResponse().withStatus(503)));
    String device = "phone-" + UUID.randomUUID();
    for (int i = 0; i < 6; i++) {
      Res r =
          login(
              UUID.randomUUID().toString(),
              device,
              CustomerSocialLoginIT.google("g-" + UUID.randomUUID()));
      assertThat(r.status()).isEqualTo(503);
      assertThat(r.body().path("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }
    // 5회 한도를 넘겨도 제한(423)이 아니다 — 제공자·의존 장애는 실패 횟수에서 뺀다. 계정도 만들지 않는다(판단이 먼저)
    // (시험 경로에는 wiremock 이 가져온 Apache HttpClient 가 있어 503 을 한 번 더 보낸다 — 호출 수는 정확히 세지 않는다)
    int asked = customer.findAll(getRequestedFor(urlPathMatching(CONSENTS))).size();
    assertThat(asked).isGreaterThanOrEqualTo(6);

    // 비회원 세션(장바구니용)은 동의와 무관 — customer 를 부르지 않는다
    var guest =
        http.post()
            .uri("/auth/guest-sessions")
            .headers(
                h -> {
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                  h.set("X-Device-Id", device);
                })
            .retrieve()
            .toEntity(String.class);
    assertThat(guest.getStatusCode().value()).isEqualTo(201);
    customer.verify(asked, getRequestedFor(urlPathMatching(CONSENTS)));
    customer.verify(0, postRequestedFor(urlPathMatching(CHANGES)));
  }
}
