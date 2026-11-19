package com.pmart.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.HashMap;
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
 * 프로필 변경(POST /me/profile-changes) — 지금은 닉네임만(2026-11-03). 즉시 반영·버전, 멱등 재시도·결과 조회, 버전 충돌, 탈퇴 신청 이후
 * 거절, 연락처는 아직 400. 실제 PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class ProfileChangeFlowIT extends PostgresKafkaContainerSupport {

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

  Res change(String token, String key, Map<String, Object> body, Integer expected) {
    var e =
        http.post()
            .uri("/me/profile-changes")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                  if (expected != null) {
                    h.set("X-Expected-Version", String.valueOf(expected));
                  }
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> nick(String v) {
    Map<String, Object> m = new HashMap<>();
    m.put("nickname", v);
    return m;
  }

  static String newCustomer() {
    return "CU-PC-" + UUID.randomUUID();
  }

  @Test
  void 닉네임을_바꾸면_바로_반영되고_결과를_다시_조회할_수_있다() {
    String token = TestJwt.customer(newCustomer());
    int v0 = get(token, "/me").body().path("version").asInt();

    String key = UUID.randomUUID().toString();
    Res res = change(token, key, nick("  장보기왕  "), v0);
    assertThat(res.status()).isEqualTo(200);
    assertThat(res.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(res.body().path("requestId").asString()).isEqualTo(key);
    assertThat(res.body().path("version").asInt()).isEqualTo(v0 + 1);

    JsonNode me = get(token, "/me").body();
    assertThat(me.path("nickname").asString()).isEqualTo("장보기왕"); // 앞뒤 공백 제거
    assertThat(me.path("version").asInt()).isEqualTo(v0 + 1);

    Res result = get(token, "/me/profile-changes/" + key);
    assertThat(result.status()).isEqualTo(200);
    assertThat(result.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(result.body().path("version").asInt()).isEqualTo(v0 + 1);
  }

  @Test
  void 같은_키_재시도는_처음_결과_같은_키_다른_본문은_409() {
    String token = TestJwt.customer(newCustomer());
    int v0 = get(token, "/me").body().path("version").asInt();
    String key = UUID.randomUUID().toString();

    assertThat(change(token, key, nick("하나"), v0).body().path("version").asInt()).isEqualTo(v0 + 1);
    // 그 사이 버전이 올랐어도(v0+1) 같은 요청의 재시도는 409 가 아니라 처음 결과
    Res retry = change(token, key, nick("하나"), v0);
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.body().path("version").asInt()).isEqualTo(v0 + 1);

    Res other = change(token, key, nick("둘"), v0);
    assertThat(other.status()).isEqualTo(409);
    assertThat(other.body().path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void 옛_버전으로_바꾸면_409_VERSION_CONFLICT() {
    String token = TestJwt.customer(newCustomer());
    int v0 = get(token, "/me").body().path("version").asInt();
    assertThat(change(token, UUID.randomUUID().toString(), nick("처음"), v0).status()).isEqualTo(200);

    Res stale = change(token, UUID.randomUUID().toString(), nick("두번째"), v0);
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.body().path("code").asString()).isEqualTo("VERSION_CONFLICT");
    assertThat(get(token, "/me").body().path("nickname").asString()).isEqualTo("처음");
  }

  @Test
  void 같은_닉네임이면_버전을_올리지_않는다() {
    String token = TestJwt.customer(newCustomer());
    int v1 =
        change(token, UUID.randomUUID().toString(), nick("그대로"), null)
            .body()
            .path("version")
            .asInt();
    Res same = change(token, UUID.randomUUID().toString(), nick("그대로"), null);
    assertThat(same.status()).isEqualTo(200);
    assertThat(same.body().path("version").asInt()).isEqualTo(v1);
  }

  @Test
  void 연락처_빈값_긴값_제어문자는_400() {
    String token = TestJwt.customer(newCustomer());
    // 휴대폰 형식이 아니면 400(0.7.0 — 그 전에는 휴대폰도 400)
    Map<String, Object> phone = new HashMap<>();
    phone.put("phone", "02-123-4567");
    Res p = change(token, UUID.randomUUID().toString(), phone, null);
    assertThat(p.status()).isEqualTo(400);
    assertThat(p.body().path("code").asString()).isEqualTo("VALIDATION_FAILED");

    assertThat(change(token, UUID.randomUUID().toString(), nick("   "), null).status())
        .isEqualTo(400);
    assertThat(change(token, UUID.randomUUID().toString(), nick("가".repeat(31)), null).status())
        .isEqualTo(400);
    assertThat(change(token, UUID.randomUUID().toString(), nick("줄\n바꿈"), null).status())
        .isEqualTo(400);
    assertThat(change(token, UUID.randomUUID().toString(), new HashMap<>(), null).status())
        .isEqualTo(400);
    assertThat(
            get(token, "/me").body().path("nickname").isNull()
                || get(token, "/me").body().path("nickname").isMissingNode())
        .isTrue();
  }

  /**
   * BR-32(0.7.0, 2026-11-19): 휴대폰을 확인 안 된 연락처로 저장 → /me 에 가리지 않은 번호·가린 번호·phoneVerified=false, 같은 값
   * 그대로, 빈 값은 지우기.
   */
  @Test
  void 휴대폰은_확인_안_된_연락처로_저장된다() {
    String token = TestJwt.customer(newCustomer());
    Map<String, Object> phone = new HashMap<>();
    phone.put("phone", "010-1234-5678");
    Res saved = change(token, UUID.randomUUID().toString(), phone, null);
    assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
    JsonNode me = get(token, "/me").body();
    assertThat(me.path("phone").asString()).isEqualTo("01012345678");
    assertThat(me.path("phoneMasked").asString()).isEqualTo("010-****-5678");
    assertThat(me.path("phoneVerified").asBoolean()).isFalse();
    int v = me.path("version").asInt();

    Map<String, Object> same = new HashMap<>();
    same.put("phone", "010 1234 5678");
    assertThat(
            change(token, UUID.randomUUID().toString(), same, null).body().path("version").asInt())
        .as("같은 번호는 그대로")
        .isEqualTo(v);

    Map<String, Object> clear = new HashMap<>();
    clear.put("phone", "");
    assertThat(change(token, UUID.randomUUID().toString(), clear, null).status()).isEqualTo(200);
    JsonNode after = get(token, "/me").body();
    assertThat(after.path("phone").isNull() || after.path("phone").isMissingNode()).isTrue();
    assertThat(after.path("phoneMasked").isNull() || after.path("phoneMasked").isMissingNode())
        .isTrue();
  }

  @Test
  void 탈퇴_신청_이후에는_409_INVALID_STATE() {
    String cu = newCustomer();
    String token = TestJwt.customer(cu);
    get(token, "/me");
    jdbc.update("UPDATE customer SET status = 'WITHDRAW_PENDING' WHERE account_id = ?", cu);

    Res res = change(token, UUID.randomUUID().toString(), nick("탈퇴중"), null);
    assertThat(res.status()).isEqualTo(409);
    assertThat(res.body().path("code").asString()).isEqualTo("INVALID_STATE");
  }

  @Test
  void 다른_고객의_요청_결과는_404() {
    String token = TestJwt.customer(newCustomer());
    String key = UUID.randomUUID().toString();
    change(token, key, nick("내것"), null);

    assertThat(get(TestJwt.customer(newCustomer()), "/me/profile-changes/" + key).status())
        .isEqualTo(404);
    assertThat(get(token, "/me/profile-changes/" + UUID.randomUUID()).status()).isEqualTo(404);
  }
}
