package com.pmart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 비회원 세션(typ=GUEST): 발급 → 같은 키 재전송은 같은 비회원 주체 → 같은 기기만 갱신 → 회원 전용 API 403(guest-paths 밖) → 자기 세션
 * 조회·로그아웃. 실제 PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test"
    })
class GuestSessionIT extends PostgresKafkaContainerSupport {

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

  Res call(HttpMethod method, String path, String token, Object body, Map<String, String> headers) {
    var spec =
        http.method(method)
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("X-Source-System", "customer-app");
                  if (method != HttpMethod.GET) {
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

  Res start(String deviceId, String key) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("X-Device-Id", deviceId);
    h.put("Idempotency-Key", key);
    return call(HttpMethod.POST, "/auth/guest-sessions", null, null, h);
  }

  JsonNode claims(String jwt) {
    return mapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
  }

  @Test
  void guestSessionLifecycle() {
    String device = "DEV-" + UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    Res started = start(device, key);
    assertThat(started.status()).isEqualTo(201);
    String guestId = started.body().path("guestId").asString();
    assertThat(guestId).startsWith("GST-");
    String access = started.body().path("tokens").path("accessToken").asString();
    JsonNode c = claims(access);
    assertThat(c.path("typ").asString()).isEqualTo("GUEST");
    assertThat(c.path("sub").asString()).isEqualTo(guestId);

    // 같은 키 재전송 → 같은 비회원 주체(새 세션)
    assertThat(start(device, key).body().path("guestId").asString()).isEqualTo(guestId);
    // 다른 키 → 새 비회원
    assertThat(start(device, UUID.randomUUID().toString()).body().path("guestId").asString())
        .isNotEqualTo(guestId);
    // X-Device-Id 없음 → 400
    assertThat(call(HttpMethod.POST, "/auth/guest-sessions", null, null, Map.of()).status())
        .isEqualTo(400);

    // 갱신은 같은 기기만, 회전
    String refresh = started.body().path("tokens").path("refreshToken").asString();
    assertThat(
            call(
                    HttpMethod.POST,
                    "/auth/tokens",
                    null,
                    Map.of("refreshToken", refresh),
                    Map.of("X-Device-Id", "OTHER"))
                .status())
        .isEqualTo(401);
    Res started2 = start(device, UUID.randomUUID().toString());
    Res refreshed =
        call(
            HttpMethod.POST,
            "/auth/tokens",
            null,
            Map.of("refreshToken", started2.body().path("tokens").path("refreshToken").asString()),
            Map.of("X-Device-Id", device));
    assertThat(refreshed.status()).isEqualTo(200);
    assertThat(claims(refreshed.body().path("accessToken").asString()).path("typ").asString())
        .isEqualTo("GUEST");

    // 회원·운영자 전용 API 는 403, 자기 세션 조회·로그아웃은 된다
    assertThat(call(HttpMethod.GET, "/operator-accounts", access, null, Map.of()).status())
        .isEqualTo(403);
    Res current = call(HttpMethod.GET, "/auth/sessions/current", access, null, Map.of());
    assertThat(current.status()).isEqualTo(200);
    assertThat(current.body().path("principalType").asString()).isEqualTo("GUEST");
    assertThat(
            call(HttpMethod.POST, "/auth/logouts", access, Map.of("allDevices", false), Map.of())
                .status())
        .isEqualTo(204);
  }
}
