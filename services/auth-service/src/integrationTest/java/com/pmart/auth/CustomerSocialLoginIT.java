package com.pmart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.pmart.auth.application.CustomerProperties;
import com.pmart.auth.infrastructure.IdTokenVerifier;
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
      // 카카오·네이버(ADR-0071) — aud = 카카오 SDK 앱 키·네이버 Client ID
      "pmart.auth.customer.kakao.client-ids=kakao-native-app-key",
      "pmart.auth.customer.naver.client-ids=naver-client-id",
      "pmart.auth.customer.max-failures=3",
      // 이 시험은 동의 단계가 꺼진 경로(바로 AUTH_MEMBER). 켜진 경로는 CustomerConsentLoginIT
      "pmart.auth.customer.require-consent=false"
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

  /** 제공자(Google·카카오·네이버) 공개키 대신 시험 키로 서명을 확인하고, 그 밖의 검증(iss·aud·만료)은 운영 규칙 그대로. */
  @TestConfiguration
  static class FakeGoogle {
    @Bean
    @Primary
    com.pmart.auth.infrastructure.IdTokenVerifiers testVerifiers(CustomerProperties props)
        throws Exception {
      return new com.pmart.auth.infrastructure.IdTokenVerifiers(
          Map.of(
              "GOOGLE", entry(props.google().clientIds(), props.google().issuers()),
              "KAKAO", entry(props.kakao().clientIds(), props.kakao().issuers()),
              "NAVER", entry(props.naver().clientIds(), props.naver().issuers())));
    }

    static com.pmart.auth.infrastructure.IdTokenVerifiers.Entry entry(
        List<String> clientIds, List<String> issuers) throws Exception {
      NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(GOOGLE_KEY.toRSAPublicKey()).build();
      d.setJwtValidator(IdTokenVerifier.validator(clientIds, issuers));
      return new com.pmart.auth.infrastructure.IdTokenVerifiers.Entry(
          new IdTokenVerifier(d), clientIds);
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
            .claim("email_verified", true)
            .claim("name", "홍길동")
            .claim("picture", "https://lh3.googleusercontent.com/a/" + sub)
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(GOOGLE_KEY)))
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("google-test").build(), claims))
        .getTokenValue();
  }

  /** 네이버처럼 프로필 클레임이 없는 ID 토큰. */
  static String bareIdToken(String sub, String aud, String iss) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(iss)
            .subject(sub)
            .audience(List.of(aud))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(GOOGLE_KEY)))
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("google-test").build(), claims))
        .getTokenValue();
  }

  Res loginWithPicture(String device, String provider, String token, String picture) {
    var e =
        http.post()
            .uri("/auth/social-logins")
            .headers(
                h -> {
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                  h.set("X-Device-Id", device);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("provider", provider, "providerToken", token, "profileImageUrl", picture))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(),
        mapper.readTree(e.getBody() == null ? "{}" : e.getBody()),
        e.getHeaders().getFirst("Retry-After"));
  }

  String authenticatedPicture(String accountId) {
    return mapper
        .readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'CustomerAuthenticated'"
                    + " AND aggregateid = ? ORDER BY created_at DESC LIMIT 1",
                String.class,
                accountId))
        .path("payload")
        .path("pictureUrl")
        .asString(null);
  }

  /** 0.8.0: 네이버 ID 토큰엔 사진이 없어 앱이 넘긴 pstatic.net 주소를 사건에 싣는다. 다른 호스트·다른 제공자는 무시(로그인은 그대로). */
  @Test
  void naverPictureFromAppOnlyForPstatic() {
    String naver =
        bareIdToken("nv-" + UUID.randomUUID(), "naver-client-id", "https://nid.naver.com");
    String pic = "https://phinf.pstatic.net/contact/profile/a.jpg";
    Res ok = loginWithPicture("phone-" + UUID.randomUUID(), "NAVER", naver, pic);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(authenticatedPicture(ok.body().path("accountId").asString())).isEqualTo(pic);

    String naver2 =
        bareIdToken("nv-" + UUID.randomUUID(), "naver-client-id", "https://nid.naver.com");
    Res evil =
        loginWithPicture(
            "phone-" + UUID.randomUUID(), "NAVER", naver2, "https://evil.example/x.jpg");
    assertThat(evil.status()).isEqualTo(200);
    assertThat(authenticatedPicture(evil.body().path("accountId").asString())).isNull();

    String kakao =
        bareIdToken("kk-" + UUID.randomUUID(), "kakao-native-app-key", "https://kauth.kakao.com");
    Res k = loginWithPicture("phone-" + UUID.randomUUID(), "KAKAO", kakao, pic);
    assertThat(k.status()).isEqualTo(200);
    assertThat(authenticatedPicture(k.body().path("accountId").asString())).isNull();
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
    // PMart API 용 aud(ADR-0055 결정 6) — Google ID 토큰의 aud(고객 웹 클라이언트 ID)를 옮기지 않는다
    assertThat(jwt.getAudience()).containsExactly("pmart-api");
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE type = 'CustomerAuthenticated' AND aggregateid = ?"
                    + " LIMIT 1",
                String.class,
                accountId))
        .isEqualTo("platform.auth.v1");
    // Google 이 준 프로필은 버리지 않고 사건으로 customer 에 넘긴다(ADR-0024)
    JsonNode payload =
        mapper
            .readTree(
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE type = 'CustomerAuthenticated'"
                        + " AND aggregateid = ? LIMIT 1",
                    String.class,
                    accountId))
            .path("payload");
    assertThat(payload.path("email").asString()).isEqualTo(sub + "@gmail.com");
    assertThat(payload.path("emailVerified").asBoolean()).isTrue();
    assertThat(payload.path("name").asString()).isEqualTo("홍길동");
    assertThat(payload.path("pictureUrl").asString()).endsWith(sub);
    // auth 는 제공자 sub 만 저장한다(이메일·이름 컬럼 없음)
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name IN"
                    + " ('customer_account','social_identity') AND column_name IN ('email','name')",
                Integer.class))
        .isZero();

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
    // 마지막 사용 기준 연장(2026-11-02): 세션이 이틀 남았어도 갱신하면 지금 + 30일로 늘어난다
    String sid = session.body().path("sessionId").asString();
    jdbc.update(
        "UPDATE auth_session SET expires_at = now() + interval '2 days' WHERE session_id = ?", sid);
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
    java.sql.Timestamp extended =
        jdbc.queryForObject(
            "SELECT expires_at FROM auth_session WHERE session_id = ?",
            java.sql.Timestamp.class,
            sid);
    assertThat(extended.toInstant())
        .isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(29)));
    assertThat(
            java.time.Instant.parse(
                mapper.readTree(refreshed.getBody()).path("refreshTokenExpiresAt").asString()))
        .isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(29)));

    // APPLE 은 아직 없음(카카오·네이버는 2026-11-19 ADR-0071 부터 지원 — kakaoAndNaverFollowTheGoogleFlow)
    assertThat(login(UUID.randomUUID().toString(), device, "APPLE", "x").status()).isEqualTo(501);
  }

  /**
   * 카카오·네이버(ADR-0071, 2026-11-19): 구글과 같은 흐름 — 제공자 ID 토큰으로 가입·재로그인, 계정은 (제공자, sub)로 따로(같은 sub 라도
   * 제공자가 다르면 다른 계정), 다른 제공자 발급자·aud 는 거절, APPLE 은 501.
   */
  @Test
  void kakaoAndNaverFollowTheGoogleFlow() {
    String sub = "same-sub-" + UUID.randomUUID();
    String kakao = idToken(sub, "kakao-native-app-key", "https://kauth.kakao.com");
    String naver = idToken(sub, "naver-client-id", "https://nid.naver.com");

    Res k1 = login(UUID.randomUUID().toString(), "phone-" + UUID.randomUUID(), "KAKAO", kakao);
    assertThat(k1.status()).as(k1.body().toString()).isEqualTo(200);
    assertThat(k1.body().path("state").asString()).isEqualTo("AUTH_MEMBER");
    String kakaoAccount = k1.body().path("accountId").asString();
    assertThat(kakaoAccount).startsWith("CU-");
    Res k2 = login(UUID.randomUUID().toString(), "phone-" + UUID.randomUUID(), "KAKAO", kakao);
    assertThat(k2.body().path("accountId").asString()).as("재로그인 = 같은 계정").isEqualTo(kakaoAccount);

    Res n1 = login(UUID.randomUUID().toString(), "phone-" + UUID.randomUUID(), "NAVER", naver);
    assertThat(n1.status()).as(n1.body().toString()).isEqualTo(200);
    assertThat(n1.body().path("accountId").asString())
        .as("제공자가 다르면 다른 계정")
        .startsWith("CU-")
        .isNotEqualTo(kakaoAccount);

    // 카카오 토큰을 네이버로, 다른 앱 키는 거절
    assertThat(
            login(UUID.randomUUID().toString(), "phone-" + UUID.randomUUID(), "NAVER", kakao)
                .status())
        .isEqualTo(401);
    assertThat(
            login(
                    UUID.randomUUID().toString(),
                    "phone-" + UUID.randomUUID(),
                    "KAKAO",
                    idToken(sub, "other-app-key", "https://kauth.kakao.com"))
                .status())
        .isEqualTo(401);
    Res apple = login(UUID.randomUUID().toString(), "phone-" + UUID.randomUUID(), "APPLE", kakao);
    assertThat(apple.status()).isEqualTo(501);
  }

  /**
   * 네이버 콜백 중계(0.5.0 BR-66 · 0.7.0 페이지): 쿼리를 바꾸지 않고 고정 앱 주소로 넘기는 페이지(200 HTML — 302 는 Chrome 이 앱을 안
   * 열었음), 캐시 금지·리퍼러 없음·CSP, 토큰 없이, 너무 긴 쿼리 400.
   */
  @Test
  void naverCallbackRelaysQueryToFixedAppAddress() throws Exception {
    var client =
        java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();
    String q = "code=a%2Bb%3D%3D&state=xY_-9.~";
    for (var c :
        List.of(
            List.of("customer", "com.pmart.customer://oauth/naver"),
            List.of("rider", "com.pmart.rider://oauth/naver"))) {
      var res =
          client.send(
              java.net.http.HttpRequest.newBuilder(
                      java.net.URI.create(
                          "http://localhost:"
                              + port
                              + "/api/v1/auth/oauth-callbacks/naver/"
                              + c.get(0)
                              + "?"
                              + q))
                  .GET()
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(res.statusCode()).isEqualTo(200);
      assertThat(res.headers().firstValue("Location")).isEmpty();
      assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
      assertThat(res.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
      assertThat(res.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
      assertThat(res.headers().firstValue("Content-Security-Policy").orElse(""))
          .contains("script-src 'sha256-");
      assertThat(res.body()).contains("href=\"" + c.get(1) + "?" + q.replace("&", "&amp;") + "\"");
    }
    var error =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(
                        "http://localhost:"
                            + port
                            + "/api/v1/auth/oauth-callbacks/naver/customer?error=access_denied&error_description=Canceled+By+User"))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(error.body())
        .contains(
            "href=\"com.pmart.customer://oauth/naver?error=access_denied&amp;error_description=Canceled+By+User\"");
    var tooLong =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(
                        "http://localhost:"
                            + port
                            + "/api/v1/auth/oauth-callbacks/naver/customer?code="
                            + "a".repeat(4100)))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.discarding());
    assertThat(tooLong.statusCode()).isEqualTo(400);
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
