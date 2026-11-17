package com.pmart.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * 주문 기본 설정(GET·PUT /me/order-preferences, 2026-11-18 계약 0.4.0 — "다음에도 이 품절 처리 적용"). 저장 전
 * null·version 0, 저장·변경·같은 값, 멱등 재시도, 버전 충돌, 잘못된 값 400, 탈퇴 신청 이후 409, 고객만, 프로필 버전과 따로. 실제
 * PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      // 컨텍스트 캐시 키를 다른 시험과 다르게 — 같은 설정이면 앞 시험 클래스의 컨텍스트(이미 멈춘 DB 컨테이너 주소)를 다시 쓴다
      "pmart.security.service-token.client-secret=order-preference-test"
    })
@Import(TestJwtConfiguration.class)
class OrderPreferenceFlowIT extends PostgresKafkaContainerSupport {

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

  Res put(String token, String key, @Nullable String value, @Nullable Integer expected) {
    Map<String, Object> body = new HashMap<>();
    body.put("outOfStockPreference", value);
    var e =
        http.put()
            .uri("/me/order-preferences")
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

  static String newCustomer() {
    return "CU-OP-" + UUID.randomUUID();
  }

  static boolean absent(JsonNode n) {
    return n.isNull() || n.isMissingNode();
  }

  @Test
  void 저장한_적_없으면_null_저장하면_바로_보이고_바꾸면_버전이_오른다() {
    String token = TestJwt.customer(newCustomer());
    Res empty = get(token, "/me/order-preferences");
    assertThat(empty.status()).isEqualTo(200);
    assertThat(absent(empty.body().path("outOfStockPreference"))).as("지어내지 않음").isTrue();
    assertThat(empty.body().path("version").asInt()).isZero();
    assertThat(absent(empty.body().path("updatedAt"))).isTrue();

    Res saved = put(token, UUID.randomUUID().toString(), "CANCEL_ALL", 0);
    assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
    assertThat(saved.body().path("outOfStockPreference").asString()).isEqualTo("CANCEL_ALL");
    assertThat(saved.body().path("version").asInt()).isEqualTo(1);
    assertThat(saved.body().path("updatedAt").asString()).isNotBlank();

    JsonNode now = get(token, "/me/order-preferences").body();
    assertThat(now.path("outOfStockPreference").asString()).isEqualTo("CANCEL_ALL");
    assertThat(now.path("version").asInt()).isEqualTo(1);

    // 같은 값은 그대로, 다른 값은 +1
    assertThat(
            put(token, UUID.randomUUID().toString(), "CANCEL_ALL", null)
                .body()
                .path("version")
                .asInt())
        .isEqualTo(1);
    Res changed = put(token, UUID.randomUUID().toString(), "CANCEL_OOS_ONLY", 1);
    assertThat(changed.body().path("outOfStockPreference").asString()).isEqualTo("CANCEL_OOS_ONLY");
    assertThat(changed.body().path("version").asInt()).isEqualTo(2);
  }

  @Test
  void 같은_키_재시도는_처음_결과_옛_버전은_409() {
    String token = TestJwt.customer(newCustomer());
    String key = UUID.randomUUID().toString();
    assertThat(put(token, key, "CANCEL_ALL", null).body().path("version").asInt()).isEqualTo(1);
    put(token, UUID.randomUUID().toString(), "CANCEL_OOS_ONLY", 1);
    // 그 사이 바뀌었어도 같은 요청의 재시도는 처음 결과
    Res retry = put(token, key, "CANCEL_ALL", null);
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.body().path("outOfStockPreference").asString()).isEqualTo("CANCEL_ALL");
    assertThat(retry.body().path("version").asInt()).isEqualTo(1);
    // 같은 키 다른 본문
    Res other = put(token, key, "CANCEL_OOS_ONLY", null);
    assertThat(other.status()).isEqualTo(409);
    assertThat(other.body().path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
    // 옛 버전
    Res stale = put(token, UUID.randomUUID().toString(), "CANCEL_ALL", 1);
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.body().path("code").asString()).isEqualTo("VERSION_CONFLICT");
    assertThat(get(token, "/me/order-preferences").body().path("outOfStockPreference").asString())
        .isEqualTo("CANCEL_OOS_ONLY");
  }

  @Test
  void 잘못된_값_빈값은_400_탈퇴_신청_이후는_409_고객만() {
    String cu = newCustomer();
    String token = TestJwt.customer(cu);
    assertThat(put(token, UUID.randomUUID().toString(), null, null).status()).isEqualTo(400);
    assertThat(put(token, UUID.randomUUID().toString(), "KEEP_ALL", null).status()).isEqualTo(400);
    assertThat(get(token, "/me/order-preferences").body().path("version").asInt()).isZero();

    // 프로필 버전과 따로 센다
    int profileVersion = get(token, "/me").body().path("version").asInt();
    put(token, UUID.randomUUID().toString(), "CANCEL_ALL", null);
    assertThat(get(token, "/me").body().path("version").asInt()).isEqualTo(profileVersion);

    jdbc.update("UPDATE customer SET status = 'WITHDRAW_PENDING' WHERE account_id = ?", cu);
    Res withdrawn = put(token, UUID.randomUUID().toString(), "CANCEL_OOS_ONLY", null);
    assertThat(withdrawn.status()).isEqualTo(409);
    assertThat(withdrawn.body().path("code").asString()).isEqualTo("INVALID_STATE");

    String operator = TestJwt.operator("op-1", List.of("OPERATOR_ADMIN"), List.of());
    assertThat(get(operator, "/me/order-preferences").status()).isEqualTo(403);
    assertThat(put(operator, UUID.randomUUID().toString(), "CANCEL_ALL", null).status())
        .isEqualTo(403);
  }
}
