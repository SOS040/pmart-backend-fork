package com.pmart.customer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
 * 직원용 고객 조회(0.11.0, CU-3·CU-4 — audit-service 는 WireMock): 고객번호 6자리(가입 때 채움·/me 에도), 번호·전화·ID 여러 개로
 * 찾기(가린 값만, 셋 중 하나만), 상세(가린 값·가입 방식·확인 여부), 원문 열람은 사유 + 열람 기록 뒤에만(기록 실패면 503 원문 없음), 권한 없는 직원·고객
 * 403.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(name = "audit", baseUrlProperties = "pmart.customer.audit-base-url"))
class StaffCustomerIT extends PostgresKafkaContainerSupport {

  static final String LOGS = "/api/v1/internal/pii-access-logs";
  static final String CS = TestJwt.operator("cs-1", List.of("CS_AGENT"), List.of());
  static final String MFC = TestJwt.operator("mfc-1", List.of("MFC_WORKER"), List.of("MFC-1"));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("audit")
  WireMockServer audit;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    audit.resetAll();
  }

  record Res(int status, JsonNode body) {}

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res reveal(String token, String customerId, String key, Object body) {
    var e =
        http.post()
            .uri("/customers/" + customerId + "/pii-reveals")
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

  /** /me 로 고객 행을 만들고(번호는 트리거가) 이름·전화·가입 방식을 채운다(로그인 사건 대신). */
  String customer(String name, String phone) {
    String id = "CU-ST-" + UUID.randomUUID();
    assertThat(get(TestJwt.customer(id), "/me").status()).isEqualTo(200);
    jdbc.update(
        "UPDATE customer SET name = ?, phone = ?, phone_verified = true, social_provider = 'KAKAO',"
            + " email = 'kim.cs@example.com' WHERE account_id = ?",
        name,
        phone,
        id);
    return id;
  }

  @Test
  void numberSearchDetailAndReveal() {
    String a = customer("김철수", "01012345678");
    String b = customer("이영", "01098765432");

    // 고객번호: 가입 때 채워지고 /me 에도
    JsonNode me = get(TestJwt.customer(a), "/me").body();
    String number = me.path("customerNumber").asString();
    assertThat(number).matches("[1-9][0-9]{5}");

    // 번호로 찾기 — 가린 값만
    JsonNode byNumber = get(CS, "/customers?customerNumber=" + number).body();
    assertThat(byNumber.path("items")).hasSize(1);
    JsonNode s = byNumber.path("items").path(0);
    assertThat(s.path("customerId").asString()).isEqualTo(a);
    assertThat(s.path("nameMasked").asString()).isEqualTo("김*수");
    assertThat(s.path("phoneMasked").asString()).isEqualTo("010-****-5678");
    assertThat(byNumber.toString()).doesNotContain("김철수").doesNotContain("01012345678");

    // 전화(하이픈 무시)·ID 여러 개(요청 순서, 없는 ID 빠짐)
    assertThat(
            get(CS, "/customers?phone=010-9876-5432").body().at("/items/0/customerId").asString())
        .isEqualTo(b);
    JsonNode batch = get(CS, "/customers?customerIds=" + b + ",CU-NONE," + a).body().path("items");
    assertThat(batch).hasSize(2);
    assertThat(batch.path(0).path("nameMasked").asString()).isEqualTo("이*");
    assertThat(batch.path(1).path("customerId").asString()).isEqualTo(a);
    // 셋 중 하나만·형식
    assertThat(get(CS, "/customers").status()).isEqualTo(400);
    assertThat(get(CS, "/customers?customerNumber=" + number + "&phone=01012345678").status())
        .isEqualTo(400);
    assertThat(get(CS, "/customers?phone=02-123-4567").status()).isEqualTo(400);
    assertThat(get(CS, "/customers?customerNumber=012345").status()).isEqualTo(400);

    // 상세
    JsonNode d = get(CS, "/customers/" + a).body();
    assertThat(d.path("customerNumber").asString()).isEqualTo(number);
    assertThat(d.path("emailMasked").asString()).isEqualTo("k***@e***");
    assertThat(d.path("socialProvider").asString()).isEqualTo("KAKAO");
    assertThat(d.path("phoneVerified").asBoolean()).isTrue();
    assertThat(d.path("status").asString()).isEqualTo("ACTIVE");
    assertThat(get(CS, "/customers/CU-NONE").status()).isEqualTo(404);

    // 권한: MFC 작업자·고객 403
    assertThat(get(MFC, "/customers?customerNumber=" + number).status()).isEqualTo(403);
    assertThat(get(TestJwt.customer(a), "/customers/" + a).status()).isEqualTo(403);

    // 원문 열람: 기록 실패(503) → 원문 없음
    audit.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(LOGS))
            .willReturn(aResponse().withStatus(503)));
    Res down = reveal(CS, a, "k-1", Map.of("reason", "주문 문의 — 연락", "fields", List.of("NAME")));
    assertThat(down.status()).isEqualTo(503);
    assertThat(down.body().toString()).doesNotContain("김철수");
    // 사유 없음 400(기록하지 않음)
    assertThat(reveal(CS, a, "k-2", Map.of("reason", " ")).status()).isEqualTo(400);

    // 기록되면 고른 항목만 원문
    audit.resetAll();
    audit.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(LOGS))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"piiAccessLogId\":\"PAL-1\",\"recordedAt\":\"2026-11-20T00:00:00Z\"}")));
    Res ok = reveal(CS, a, "k-3", Map.of("reason", "주문 문의 — 연락", "fields", List.of("NAME")));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("name").asString()).isEqualTo("김철수");
    assertThat(ok.body().path("phone").isNull()).isTrue();
    assertThat(ok.body().path("piiAccessLogId").asString()).isEqualTo("PAL-1");
    audit.verify(
        postRequestedFor(urlPathEqualTo(LOGS))
            .withRequestBody(matchingJsonPath("$.subjectType", equalToStr("CUSTOMER")))
            .withRequestBody(matchingJsonPath("$.subjectRef", equalToStr(a)))
            .withRequestBody(matchingJsonPath("$.caseRef", equalToStr("CUSTOMER:" + a)))
            .withRequestBody(matchingJsonPath("$.fields[0]", equalToStr("NAME")))
            .withRequestBody(matchingJsonPath("$.actor.actorId", equalToStr("cs-1"))));
  }

  static com.github.tomakehurst.wiremock.matching.StringValuePattern equalToStr(String v) {
    return com.github.tomakehurst.wiremock.client.WireMock.equalTo(v);
  }
}
