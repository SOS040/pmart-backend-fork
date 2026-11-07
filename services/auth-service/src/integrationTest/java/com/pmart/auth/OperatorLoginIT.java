package com.pmart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 운영자 로그인 E2E(ADR-0018): 첫 관리자(설정값) 로그인 → 계정 생성 → 그 계정 로그인 → JWKS 로 검증되는 typ=OPERATOR 토큰 → refresh
 * 회전·재사용 감지 → 로그아웃 → 잠금·비활성화·비밀번호 초기화. 실제 PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.auth.operator.max-failed-attempts=3",
      "pmart.auth.operator.bootstrap.login-id=admin",
      // pmart-local
      "pmart.auth.operator.bootstrap.password-hash={bcrypt}$2a$10$Qut13u4vwUA83/mKRLIfwOJFI5P5ZJWUVbSYxzWim244YpORIaYKG",
      // 개발 환경 전용 계정(LAN 프로필에만 — 2026-11-07), 같은 시험 비밀번호 pmart-local
      "pmart.auth.operator.dev-accounts[0].login-id=claude-dev",
      "pmart.auth.operator.dev-accounts[0].password-hash={bcrypt}$2a$10$Qut13u4vwUA83/mKRLIfwOJFI5P5ZJWUVbSYxzWim244YpORIaYKG",
      "pmart.auth.operator.dev-accounts[0].roles[0]=OPERATOR_ADMIN"
    })
class OperatorLoginIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

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

  Res call(String method, String path, String token, Object body, Map<String, String> headers) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("X-Source-System", "backoffice-web");
                  if (!"GET".equals(method)) {
                    h.set("Idempotency-Key", UUID.randomUUID().toString());
                  }
                  headers.forEach(h::set);
                });
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    var e = spec.retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(),
        mapper.readTree(e.getBody() == null || e.getBody().isBlank() ? "{}" : e.getBody()));
  }

  Res login(String loginId, String password) {
    var e =
        http.post()
            .uri("/auth/operator-logins")
            .headers(
                h -> {
                  h.set("X-Source-System", "backoffice-web");
                  h.set("X-Device-Id", "browser-1");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("loginId", loginId, "password", password))
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), mapper.readTree(e.getBody()));
  }

  Res refresh(String refreshToken) {
    return call(
        "POST",
        "/auth/tokens",
        null,
        Map.of("refreshToken", refreshToken),
        Map.of("X-Device-Id", "browser-1"));
  }

  @Test
  void adminCreatesOperatorWhoLogsInRefreshesAndLogsOut() {
    // 첫 관리자(설정값)로 로그인 — 비밀번호 변경 필요 표시
    Res admin = login("admin", "pmart-local");
    assertThat(admin.status()).isEqualTo(200);
    assertThat(admin.body().path("operator").path("passwordChangeRequired").asBoolean()).isTrue();
    String adminToken = admin.body().path("tokens").path("accessToken").asString();

    // 계정 생성(관리자만)
    String loginId = "md" + (System.nanoTime() % 100000);
    Res created =
        call(
            "POST",
            "/operator-accounts",
            adminToken,
            Map.of(
                "loginId",
                loginId,
                "displayName",
                "MD 김",
                "initialPassword",
                "init-pass-1234",
                "roles",
                List.of("MD"),
                "centers",
                List.of("MFC-BSN-01")),
            Map.of());
    assertThat(created.status()).isEqualTo(201);
    String operatorId = created.body().path("operatorId").asString();
    assertThat(
            call(
                    "POST",
                    "/operator-accounts",
                    adminToken,
                    Map.of(
                        "loginId",
                        loginId,
                        "displayName",
                        "x",
                        "initialPassword",
                        "init-pass-1234",
                        "roles",
                        List.of("MD")),
                    Map.of())
                .body()
                .path("code")
                .asString())
        .isEqualTo("LOGIN_ID_TAKEN");
    assertThat(
            call(
                    "POST",
                    "/operator-accounts",
                    adminToken,
                    Map.of(
                        "loginId",
                        loginId + "x",
                        "displayName",
                        "x",
                        "initialPassword",
                        "short",
                        "roles",
                        List.of("MD")),
                    Map.of())
                .body()
                .path("code")
                .asString())
        .isEqualTo("PASSWORD_POLICY");

    // MD 로그인 → 토큰은 JWKS 로 검증되고 typ/roles/centers/sid 를 싣는다
    Res md = login(loginId, "init-pass-1234");
    assertThat(md.status()).isEqualTo(200);
    String access = md.body().path("tokens").path("accessToken").asString();
    String refresh1 = md.body().path("tokens").path("refreshToken").asString();
    Jwt jwt =
        NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port + "/api/v1/internal/jwks")
            .build()
            .decode(access);
    assertThat(jwt.getSubject()).isEqualTo(operatorId);
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("OPERATOR");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("MD");
    assertThat(jwt.getClaimAsStringList("centers")).containsExactly("MFC-BSN-01");
    assertThat(jwt.getClaimAsString("sid")).startsWith("SES-");

    // 현재 세션, MD 는 계정 관리 불가
    Res session = call("GET", "/auth/sessions/current", access, null, Map.of());
    assertThat(session.body().path("principalType").asString()).isEqualTo("OPERATOR");
    assertThat(session.body().path("roles").path(0).asString()).isEqualTo("MD");
    assertThat(call("GET", "/operator-accounts", access, null, Map.of()).status()).isEqualTo(403);

    // 역할 변경은 refresh 때 반영, refresh 회전, 쓴 토큰 재사용 → 세션 폐기
    Res updated =
        call(
            "PUT",
            "/operator-accounts/" + operatorId,
            adminToken,
            Map.of(
                "displayName",
                "MD 김",
                "roles",
                List.of("MD", "PRICE_APPROVER"),
                "centers",
                List.of(),
                "state",
                "ACTIVE"),
            Map.of());
    assertThat(updated.status()).isEqualTo(200);
    assertThat(updated.body().path("version").asInt()).isEqualTo(2);
    Res r2 = refresh(refresh1);
    assertThat(r2.status()).isEqualTo(200);
    String refresh2 = r2.body().path("refreshToken").asString();
    assertThat(refresh2).isNotEqualTo(refresh1);
    Jwt refreshed =
        NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port + "/api/v1/internal/jwks")
            .build()
            .decode(r2.body().path("accessToken").asString());
    assertThat(refreshed.getClaimAsStringList("roles")).containsExactly("MD", "PRICE_APPROVER");
    Res reused = refresh(refresh1);
    assertThat(reused.status()).isEqualTo(401);
    assertThat(reused.body().path("code").asString()).isEqualTo("TOKEN_REUSED");
    assertThat(refresh(refresh2).status()).isEqualTo(401); // 세션이 폐기됐으니 새 토큰도 무효

    // 다시 로그인 → 로그아웃하면 그 세션의 refresh 불가
    Res again = login(loginId, "init-pass-1234");
    String access3 = again.body().path("tokens").path("accessToken").asString();
    assertThat(call("POST", "/auth/logouts", access3, Map.of(), Map.of()).status()).isEqualTo(204);
    assertThat(refresh(again.body().path("tokens").path("refreshToken").asString()).status())
        .isEqualTo(401);

    // 내 비밀번호 변경
    Res relogin = login(loginId, "init-pass-1234");
    String access4 = relogin.body().path("tokens").path("accessToken").asString();
    assertThat(
            call(
                    "POST",
                    "/auth/password-changes",
                    access4,
                    Map.of("currentPassword", "wrong-pass-0", "newPassword", "new-pass-5678"),
                    Map.of())
                .status())
        .isEqualTo(401);
    assertThat(
            call(
                    "POST",
                    "/auth/password-changes",
                    access4,
                    Map.of("currentPassword", "init-pass-1234", "newPassword", "new-pass-5678"),
                    Map.of())
                .status())
        .isEqualTo(204);
    assertThat(login(loginId, "init-pass-1234").status()).isEqualTo(401);
    assertThat(login(loginId, "new-pass-5678").status()).isEqualTo(200);
  }

  @Test
  void devAccountIsCreatedOnceAndLogsInWithoutPasswordChange() {
    Res r = login("claude-dev", "pmart-local");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("operator").path("roles").toString()).contains("OPERATOR_ADMIN");
    assertThat(r.body().path("operator").path("passwordChangeRequired").asBoolean()).isFalse();
  }

  @Test
  void lockoutDisableAndPasswordReset() {
    String adminToken =
        login("admin", "pmart-local").body().path("tokens").path("accessToken").asString();
    String loginId = "lk" + (System.nanoTime() % 100000);
    String operatorId =
        call(
                "POST",
                "/operator-accounts",
                adminToken,
                Map.of(
                    "loginId",
                    loginId,
                    "displayName",
                    "잠금 시험",
                    "initialPassword",
                    "init-pass-1234",
                    "roles",
                    List.of("MD")),
                Map.of())
            .body()
            .path("operatorId")
            .asString();

    // 없는 아이디와 틀린 비밀번호는 같은 응답
    assertThat(login("nobody-here", "whatever-123").body().path("code").asString())
        .isEqualTo("LOGIN_FAILED");
    // 3회 실패 → 잠금(맞는 비밀번호도 거절)
    for (int i = 0; i < 3; i++) {
      assertThat(login(loginId, "wrong-pass-" + i).status()).isEqualTo(401);
    }
    Res locked = login(loginId, "init-pass-1234");
    assertThat(locked.status()).isEqualTo(423);
    assertThat(locked.body().path("code").asString()).isEqualTo("ACCOUNT_LOCKED");

    // 관리자 초기화 → 잠금 해제, 새 임시 비밀번호로 로그인
    assertThat(
            call(
                    "POST",
                    "/operator-accounts/" + operatorId + "/password-resets",
                    adminToken,
                    Map.of("temporaryPassword", "temp-pass-9999"),
                    Map.of())
                .status())
        .isEqualTo(204);
    Res afterReset = login(loginId, "temp-pass-9999");
    assertThat(afterReset.status()).isEqualTo(200);
    assertThat(afterReset.body().path("operator").path("passwordChangeRequired").asBoolean())
        .isTrue();

    // 비활성화 → 로그인 불가, 열린 세션의 refresh 불가
    call(
        "PUT",
        "/operator-accounts/" + operatorId,
        adminToken,
        Map.of(
            "displayName",
            "잠금 시험",
            "roles",
            List.of("MD"),
            "centers",
            List.of(),
            "state",
            "DISABLED"),
        Map.of());
    assertThat(login(loginId, "temp-pass-9999").status()).isEqualTo(401);
    assertThat(refresh(afterReset.body().path("tokens").path("refreshToken").asString()).status())
        .isEqualTo(401);

    // 관리자는 자기 자신을 비활성화할 수 없다
    String adminId =
        call("GET", "/auth/sessions/current", adminToken, null, Map.of())
            .body()
            .path("principalId")
            .asString();
    assertThat(
            call(
                    "PUT",
                    "/operator-accounts/" + adminId,
                    adminToken,
                    Map.of(
                        "displayName",
                        "관리자",
                        "roles",
                        List.of("OPERATOR_ADMIN"),
                        "centers",
                        List.of(),
                        "state",
                        "DISABLED"),
                    Map.of())
                .status())
        .isEqualTo(409);
  }
}
