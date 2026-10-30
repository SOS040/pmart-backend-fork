package com.pmart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.pmart.auth.application.CustomerProperties;
import com.pmart.auth.infrastructure.GoogleIdTokenVerifier;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 Google 로그인 E2E: 시험용 RSA 키로 만든 Google 형식 ID 토큰(운영과 같은 iss·aud·만료 검증) → 신규 가입·재로그인·같은 키 재전송·결과
 * 조회·CUSTOMER 토큰·갱신·CustomerAuthenticated(Outbox) → 거절 누적 제한(AUTH_RESTRICTED). 실제 PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.auth.customer.google.client-ids=app-client.apps.googleusercontent.com,web-client.apps.googleusercontent.com",
      "pmart.auth.customer.max-failures=3"
    })
@Import(CustomerSocialLoginIT.FakeGoogle.class)
class CustomerSocialLoginIT extends PostgresKafkaContainerSupport {

  static final RSAKey GOOGLE_KEY = generate();

  static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).keyID("google-test").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Google 공개키 대신 시험 키로 서명을 확인하고, 그 밖의 검증은 운영 규칙 그대로. */
  @TestConfiguration
  static class FakeGoogle {
    @Bean
    @Primary
    GoogleIdTokenVerifier testGoogleVerifier(CustomerProperties props) throws Exception {
      NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(GOOGLE_KEY.toRSAPublicKey()).build();
      d.setJwtValidator(GoogleIdTokenVerifier.validator(props.google()));
      return new GoogleIdTokenVerifier(d);
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body, String retryAfter) {}

  static String idToken(String sub, String aud, String iss) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(iss)
            .subject(sub)
            .audience(List.of(aud))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .claim("email", sub + "@gmail.com")
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(GOOGLE_KEY)))
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("google-test").build(), claims))
        .getTokenValue();
  }

  static String google(String sub) {
    return idToken(sub, "web-client.apps.googleusercontent.com", "https://accounts.google.com");
  }

  Res login(String key, String device, String provider, String token) {
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
            .body(Map.of("provider", provider, "providerToken", token))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(),
        mapper.readTree(e.getBody() == null ? "{}" : e.getBody()),
        e.getHeaders().getFirst("Retry-After"));
  }

  Res get(String path, String device, String token) {
    var e =
        http.get()
            .uri(path)
            .headers(
                h -> {
                  if (device != null) {
                    h.set("X-Device-Id", device);
                  }
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                })
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()), null);
  }

  Jwt decode(String accessToken) {
    return NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port + "/api/v1/internal/jwks")
        .build()
        .decode(accessToken);
  }

  @Test
  void googleSignInCreatesThenReusesAccount() {
    String sub = "g-" + UUID.randomUUID();
    String device = "phone-" + UUID.randomUUID();

    // 신규: 바로 회원(필수 동의 단계 없음 — require-consent=false)
    String key1 = UUID.randomUUID().toString();
    Res first = login(key1, device, "GOOGLE", google(sub));
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    assertThat(first.body().path("newAccount").asBoolean()).isTrue();
    String accountId = first.body().path("accountId").asString();
    assertThat(accountId).startsWith("CU-");
    Jwt jwt = decode(first.body().path("tokens").path("accessToken").asString());
    assertThat(jwt.getSubject()).isEqualTo(accountId);
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("CUSTOMER");
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE type = 'CustomerAuthenticated' AND aggregateid = ?"
                    + " LIMIT 1",
                String.class,
                accountId))
        .isEqualTo("platform.auth.v1");

    // 같은 키 재전송: 같은 원인증, 새 토큰
    Res replay = login(key1, device, "GOOGLE", google(sub));
    assertThat(replay.body().path("authRequestId").asString())
        .isEqualTo(first.body().path("authRequestId").asString());
    assertThat(replay.body().path("tokens").path("accessToken").asString()).isNotBlank();

    // 다시 로그인: 같은 계정, 신규 아님. 앱 클라이언트 aud 도 허용
    Res again =
        login(
            UUID.randomUUID().toString(),
            device,
            "GOOGLE",
            idToken(sub, "app-client.apps.googleusercontent.com", "accounts.google.com"));
    assertThat(again.body().path("accountId").asString()).isEqualTo(accountId);
    assertThat(again.body().path("newAccount").asBoolean()).isFalse();

    // 결과 조회: 같은 기기만, 토큰은 다시 주지 않는다
    String arId = first.body().path("authRequestId").asString();
    Res mine = get("/auth/social-logins/" + arId, device, null);
    assertThat(mine.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    assertThat(mine.body().path("tokens").path("accessToken").isMissingNode()).isTrue();
    assertThat(get("/auth/social-logins/" + arId, "other-device", null).status()).isEqualTo(403);

    // 고객 토큰으로 현재 세션·갱신
    String access = again.body().path("tokens").path("accessToken").asString();
    Res session = get("/auth/sessions/current", null, access);
    assertThat(session.body().path("principalType").asString()).isEqualTo("CUSTOMER");
    assertThat(session.body().path("principalId").asString()).isEqualTo(accountId);
    var refreshed =
        http.post()
            .uri("/auth/tokens")
            .headers(
                h -> {
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                  h.set("X-Device-Id", device);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of("refreshToken", again.body().path("tokens").path("refreshToken").asString()))
            .retrieve()
            .toEntity(String.class);
    assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
    assertThat(
            decode(mapper.readTree(refreshed.getBody()).path("accessToken").asString())
                .getSubject())
        .isEqualTo(accountId);

    // 다른 제공자는 아직 없음
    assertThat(login(UUID.randomUUID().toString(), device, "KAKAO", "x").status()).isEqualTo(501);
  }

  @Test
  void rejectedTokensRestrictTheDeviceAndRestrictionDoesNotExtend() {
    String device = "phone-" + UUID.randomUUID();
    String wrongAud =
        idToken("g-x", "someone-else.apps.googleusercontent.com", "https://accounts.google.com");

    Res r1 = login(UUID.randomUUID().toString(), device, "GOOGLE", wrongAud);
    assertThat(r1.status()).isEqualTo(401);
    assertThat(r1.body().path("code").asString()).isEqualTo("PROVIDER_REJECTED");
    assertThat(login(UUID.randomUUID().toString(), device, "GOOGLE", "not-a-jwt").status())
        .isEqualTo(401);
    // 3번째 실패(시험 한도 3) → 제한
    Res r3 = login(UUID.randomUUID().toString(), device, "GOOGLE", wrongAud);
    assertThat(r3.status()).isEqualTo(423);
    assertThat(r3.body().path("code").asString()).isEqualTo("AUTH_RESTRICTED");
    assertThat(r3.body().path("retryAfterSeconds").asInt()).isBetween(590, 600);
    assertThat(r3.retryAfter()).isNotBlank();
    String until = r3.body().path("restrictedUntil").asString();

    // 제한 중에는 맞는 토큰도 거절, 제한 시각은 늘지 않는다
    Res during = login(UUID.randomUUID().toString(), device, "GOOGLE", google("g-ok"));
    assertThat(during.status()).isEqualTo(423);
    assertThat(during.body().path("restrictedUntil").asString()).isEqualTo(until);

    // 다른 기기는 영향 없음
    assertThat(
            login(
                    UUID.randomUUID().toString(),
                    "phone-" + UUID.randomUUID(),
                    "GOOGLE",
                    google("g-ok"))
                .status())
        .isEqualTo(200);
  }
}
