package com.pmart.employeeauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 로컬 개발 전용 직원 토큰 발급(2026-11-13): dev-token-mint 를 켜면 운영자 ID·역할·센터로 바로 토큰, 모르는 역할은 400, 같은 운영자를 다시
 * 부르면 역할이 바뀐다. 끄면(기본) 경로가 없다 — ServiceBootIT 쪽 설정.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.employee-auth.issuer=http://employee-auth.test",
      "pmart.employee-auth.dev-token-mint.enabled=true",
      "pmart.test.context=dev-mint"
    })
class DevMintIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  final JsonMapper mapper = JsonMapper.builder().build();

  record Res(int status, JsonNode body) {}

  Res mint(Map<String, Object> body) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1")
        .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
        .build()
        .post()
        .uri("/internal/dev-tokens")
        .headers(
            h -> {
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "local-dev");
              h.set("X-Device-Id", "dev-1");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange(
            (q, r) ->
                new Res(r.getStatusCode().value(), mapper.readTree(r.getBody().readAllBytes())));
  }

  JsonNode claims(JsonNode res) {
    String t = res.path("tokens").path("accessToken").asText();
    return mapper.readTree(Base64.getUrlDecoder().decode(t.split("[.]")[1]));
  }

  @Test
  void mintsStaffTokenWithGivenRolesAndCenters() {
    String op = "OP-DEV-" + UUID.randomUUID();
    Res r =
        mint(
            Map.of("operatorId", op, "roles", List.of("MFC_WORKER"), "centers", List.of("MFC-01")));
    assertThat(r.status()).isEqualTo(200);
    JsonNode c = claims(r.body());
    assertThat(c.path("sub").asText()).isEqualTo(op);
    assertThat(c.path("typ").asText()).isEqualTo("OPERATOR");
    assertThat(c.path("iss").asText()).isEqualTo("http://employee-auth.test");
    assertThat(c.path("roles").toString()).contains("MFC_WORKER");
    assertThat(c.path("centers").toString()).contains("MFC-01");

    Res again = mint(Map.of("operatorId", op, "roles", List.of("MD", "MFC_SUPERVISOR")));
    assertThat(claims(again.body()).path("roles").toString())
        .contains("MD")
        .contains("MFC_SUPERVISOR")
        .doesNotContain("MFC_WORKER");

    assertThat(mint(Map.of("operatorId", op, "roles", List.of("NO_SUCH_ROLE"))).status())
        .isEqualTo(400);
  }
}
