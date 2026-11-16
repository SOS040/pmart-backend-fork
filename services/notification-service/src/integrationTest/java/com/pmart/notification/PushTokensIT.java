package com.pmart.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 단말 푸시 토큰(0.3.0, BR-09): 등록·같은 단말 교체·다른 주인으로 옮김·남의 단말은 못 보고 못 지움·단말 수 초과 시 가장 오래된 것 삭제·운영자 403·잘못된
 * deviceId 400·응답에 토큰 없음. 설정 max-devices = 5(시험 사본).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class PushTokensIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired DataSource dataSource;

  record Res(int status, String raw, JsonNode body) {}

  Res send(String method, String token, String path, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange(
        (req, res) -> {
          byte[] b = res.getBody().readAllBytes();
          String raw = new String(b, java.nio.charset.StandardCharsets.UTF_8);
          return new Res(
              res.getStatusCode().value(),
              raw,
              b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
        });
  }

  Res register(String jwt, String deviceId, String pushToken) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("token", pushToken);
    body.put("platform", "ANDROID");
    body.put("appVersion", "1.0.0");
    return send("PUT", jwt, "/me/push-tokens/" + deviceId, body);
  }

  List<String> devices(String jwt) {
    Res r = send("GET", jwt, "/me/push-tokens", null);
    assertThat(r.status()).isEqualTo(200);
    List<String> ids = new ArrayList<>();
    r.body().path("items").forEach(n -> ids.add(n.path("deviceId").asString()));
    return ids;
  }

  /** 저장된 토큰 원문(시험에서만 읽는다 — 운영 코드에는 읽는 질의가 없다). */
  @Nullable String storedToken(String ownerType, String ownerId, String deviceId)
      throws SQLException {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT token FROM push_token WHERE owner_type = ? AND owner_id = ? AND device_id = ?")) {
      ps.setString(1, ownerType);
      ps.setString(2, ownerId);
      ps.setString(3, deviceId);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  int rowsWithToken(String token) throws SQLException {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement("SELECT count(*) FROM push_token WHERE token = ?")) {
      ps.setString(1, token);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  static String uid() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void registerReplaceListAndUnregister() throws Exception {
    String me = "ACC-push-" + uid();
    String jwt = TestJwt.customer(me);
    String t1 = "fcm-token-1-" + UUID.randomUUID();
    String t2 = "fcm-token-2-" + UUID.randomUUID();

    Res first = register(jwt, "dev-A_1.x:y", t1);
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().path("deviceId").asString()).isEqualTo("dev-A_1.x:y");
    assertThat(first.body().path("platform").asString()).isEqualTo("ANDROID");
    assertThat(first.body().has("token")).isFalse();
    assertThat(first.raw()).doesNotContain(t1);
    String registeredAt = first.body().path("registeredAt").asString();

    // 같은 단말 다시 등록 → 토큰만 바뀌고 처음 등록 시각은 그대로, 줄은 하나
    Res again = register(jwt, "dev-A_1.x:y", t2);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.raw()).doesNotContain(t2);
    assertThat(again.body().path("registeredAt").asString()).isEqualTo(registeredAt);
    assertThat(storedToken("CUSTOMER", me, "dev-A_1.x:y")).isEqualTo(t2);
    assertThat(devices(jwt)).containsExactly("dev-A_1.x:y");

    // 같은 토큰을 다시 보내도 그대로(멱등)
    assertThat(register(jwt, "dev-A_1.x:y", t2).status()).isEqualTo(200);
    assertThat(devices(jwt)).containsExactly("dev-A_1.x:y");

    // 목록에도 토큰 없음
    Res list = send("GET", jwt, "/me/push-tokens", null);
    assertThat(list.raw()).doesNotContain(t1).doesNotContain(t2).doesNotContain("\"token\"");

    // 해제 204, 다시 해제해도 204
    assertThat(send("DELETE", jwt, "/me/push-tokens/dev-A_1.x:y", null).status()).isEqualTo(204);
    assertThat(send("DELETE", jwt, "/me/push-tokens/dev-A_1.x:y", null).status()).isEqualTo(204);
    assertThat(devices(jwt)).isEmpty();
  }

  @Test
  void tokenMovesBetweenOwnersAndOthersCannotTouchIt() throws Exception {
    String alice = "ACC-push-" + uid();
    String rider = "RDR-push-" + uid();
    String aliceJwt = TestJwt.customer(alice);
    String riderJwt = TestJwt.rider(rider);
    String shared = "fcm-shared-" + UUID.randomUUID();

    assertThat(register(aliceJwt, "phone-1", shared).status()).isEqualTo(200);
    assertThat(devices(aliceJwt)).containsExactly("phone-1");

    // 남(같은 deviceId 라도)은 고객의 단말을 못 보고 못 지운다
    String otherJwt = TestJwt.customer("ACC-push-other-" + uid());
    assertThat(devices(otherJwt)).isEmpty();
    assertThat(send("DELETE", otherJwt, "/me/push-tokens/phone-1", null).status()).isEqualTo(204);
    assertThat(storedToken("CUSTOMER", alice, "phone-1")).isEqualTo(shared);

    // 같은 토큰을 라이더가 등록 → 고객 줄은 지워지고 라이더에게 옮겨진다
    assertThat(register(riderJwt, "phone-1", shared).status()).isEqualTo(200);
    assertThat(devices(aliceJwt)).isEmpty();
    assertThat(devices(riderJwt)).containsExactly("phone-1");
    assertThat(storedToken("RIDER", rider, "phone-1")).isEqualTo(shared);

    // 같은 주인의 다른 단말로 옮겨도 한 줄만
    assertThat(register(riderJwt, "phone-2", shared).status()).isEqualTo(200);
    assertThat(devices(riderJwt)).containsExactly("phone-2");
    assertThat(rowsWithToken(shared)).isEqualTo(1);
  }

  @Test
  void oldestDeviceIsDroppedBeyondMaxDevices() {
    String me = "ACC-push-" + uid();
    String jwt = TestJwt.customer(me);
    for (int i = 1; i <= 5; i++) {
      assertThat(register(jwt, "d" + i, "fcm-" + i + "-" + UUID.randomUUID()).status())
          .isEqualTo(200);
    }
    assertThat(devices(jwt)).hasSize(5);
    // d1 을 다시 등록 → 가장 오래된 것은 d2 가 된다
    assertThat(register(jwt, "d1", "fcm-1b-" + UUID.randomUUID()).status()).isEqualTo(200);
    assertThat(register(jwt, "d6", "fcm-6-" + UUID.randomUUID()).status()).isEqualTo(200);
    assertThat(devices(jwt)).containsExactlyInAnyOrder("d1", "d3", "d4", "d5", "d6");
    assertThat(devices(jwt).getFirst()).isEqualTo("d6");
  }

  @Test
  void operatorForbiddenAndInvalidInputRejected() {
    String op = TestJwt.operator("op-push", List.of("OPERATOR_ADMIN"), List.of());
    assertThat(register(op, "dev-1", "fcm-op").status()).isEqualTo(403);
    assertThat(send("GET", op, "/me/push-tokens", null).status()).isEqualTo(403);
    assertThat(send("DELETE", op, "/me/push-tokens/dev-1", null).status()).isEqualTo(403);

    String jwt = TestJwt.customer("ACC-push-" + uid());
    assertThat(register(jwt, "bad!id", "fcm-x").status()).isEqualTo(400);
    assertThat(register(jwt, "a".repeat(129), "fcm-x").status()).isEqualTo(400);
    assertThat(send("DELETE", jwt, "/me/push-tokens/bad!id", null).status()).isEqualTo(400);
    // 빈 토큰·없는 플랫폼
    assertThat(register(jwt, "dev-1", "").status()).isEqualTo(400);
    assertThat(
            send("PUT", jwt, "/me/push-tokens/dev-1", Map.of("token", "fcm-x", "platform", "IOS"))
                .status())
        .isEqualTo(400);
    assertThat(devices(jwt)).isEmpty();
  }
}
