package com.pmart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * [LAN 시험 전용] 개발용 고객 토큰(0.3.0, 2026-11-19 MFC 시뮬레이터 R-21): 허용 목록의 기존 ACTIVE 고객만 — 토큰
 * typ=CUSTOMER·주체·15분 이하, 세션 행(발급 기록), 목록 밖 403, auth 에 없는 계정 404, ACTIVE 아님 403. 꺼진 경로(404)는 다른 시험
 * 컨텍스트가 그대로(빈 없음).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.auth.customer.google.client-ids=web-client.apps.googleusercontent.com",
      "pmart.auth.customer.require-consent=false",
      "pmart.auth.dev-customer-token.enabled=true",
      "pmart.auth.dev-customer-token.allowed-accounts=CU-DEV-OK,CU-DEV-MISSING,CU-DEV-LOCKED",
      "pmart.auth.dev-customer-token.ttl=PT15M"
    })
class DevCustomerTokenIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtDecoder decoder;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    account("CU-DEV-OK", "ACTIVE");
    account("CU-DEV-LOCKED", "WITHDRAWN");
    account("CU-DEV-OUTSIDE", "ACTIVE");
  }

  void account(String id, String state) {
    jdbc.update(
        "INSERT INTO customer_account (account_id, state, created_at, last_login_at)"
            + " VALUES (?,?,?,?) ON CONFLICT (account_id) DO NOTHING",
        id,
        state,
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));
  }

  record Res(int status, JsonNode body) {}

  Res issue(String accountId, String device) {
    var e =
        http.post()
            .uri("/internal/dev-customer-tokens")
            .headers(
                h -> {
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "mfc-simulator");
                  h.set("X-Device-Id", device);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("accountId", accountId))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  @Test
  void allowedActiveCustomerGetsShortCustomerToken() {
    String device = "sim-" + UUID.randomUUID();
    Res r = issue("CU-DEV-OK", device);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    Jwt jwt = decoder.decode(r.body().path("accessToken").asString());
    assertThat(jwt.getSubject()).isEqualTo("CU-DEV-OK");
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("CUSTOMER");
    assertThat(jwt.getExpiresAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(15 * 60 + 5));
    // 발급 기록 = 세션 행(계정·기기·호출한 곳)
    Map<String, Object> s =
        jdbc.queryForMap(
            "SELECT principal_id, device_id, source_system FROM auth_session WHERE session_id = ?",
            r.body().path("sessionId").asString());
    assertThat(s.get("principal_id")).isEqualTo("CU-DEV-OK");
    assertThat(s.get("device_id")).isEqualTo(device);
    assertThat(s.get("source_system")).isEqualTo("mfc-simulator");
  }

  @Test
  void outsideListMissingOrInactiveIsRefused() {
    Res outside = issue("CU-DEV-OUTSIDE", "sim-1");
    assertThat(outside.status()).isEqualTo(403);
    assertThat(outside.body().path("code").asString()).isEqualTo("DEV_CUSTOMER_NOT_ALLOWED");
    assertThat(issue("CU-DEV-MISSING", "sim-1").status()).isEqualTo(404);
    Res locked = issue("CU-DEV-LOCKED", "sim-1");
    assertThat(locked.status()).isEqualTo(403);
    assertThat(locked.body().path("code").asString()).isEqualTo("ACCOUNT_NOT_AVAILABLE");
  }
}
