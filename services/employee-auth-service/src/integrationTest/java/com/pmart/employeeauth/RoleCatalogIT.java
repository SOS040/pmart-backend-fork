package com.pmart.employeeauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 직원 역할 목록(0.5.0, BR-57): 설정 roles 순서·role-info 이름·센터 지정 그대로, 토큰 없으면 401, 역할 없는(HR_ADMIN 아닌) 직원
 * 토큰으로도 200. 토큰은 로컬 개발 발급(DevMintIT 와 같은 설정, 시험 문맥은 따로 — 컨테이너가 클래스마다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.employee-auth.issuer=http://employee-auth.test",
      "pmart.employee-auth.dev-token-mint.enabled=true",
      "pmart.test.context=role-catalog"
    })
class RoleCatalogIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  final JsonMapper mapper = JsonMapper.builder().build();

  record Res(int status, JsonNode body) {}

  RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1")
        .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
        .build();
  }

  String mint(List<String> roles) {
    return client()
        .post()
        .uri("/internal/dev-tokens")
        .headers(
            h -> {
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "local-dev");
              h.set("X-Device-Id", "dev-1");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("operatorId", "OP-ROLES-" + UUID.randomUUID(), "roles", roles))
        .exchange((q, r) -> mapper.readTree(r.getBody().readAllBytes()))
        .path("tokens")
        .path("accessToken")
        .asText();
  }

  Res roles(@Nullable String token) {
    return client()
        .get()
        .uri("/employee-auth/roles")
        .headers(
            h -> {
              if (token != null) {
                h.setBearerAuth(token);
              }
            })
        .exchange(
            (q, r) -> {
              byte[] b = r.getBody().readAllBytes();
              return new Res(r.getStatusCode().value(), b.length == 0 ? null : mapper.readTree(b));
            });
  }

  @Test
  void listsConfiguredRolesInOrderWithNamesAndCenterScope() {
    Res r = roles(mint(List.of("MD"))); // HR_ADMIN 이 아닌 직원도 본다
    assertThat(r.status()).isEqualTo(200);
    JsonNode items = r.body().path("items");
    List<String> codes = new ArrayList<>();
    items.forEach(i -> codes.add(i.path("code").asText()));
    assertThat(codes)
        .containsExactly(
            "OPERATOR_ADMIN",
            "HR_ADMIN",
            "CENTER_MASTER_APPROVER",
            "CS_AGENT",
            "DISPLAY_APPROVER",
            "INVENTORY_APPROVER",
            "MD",
            "MFC_SUPERVISOR",
            "MFC_WORKER",
            "PRICE_APPROVER",
            "PRODUCT_APPROVER",
            "RIDER_OPS",
            "SERVICEABILITY_APPROVER");
    for (int n = 0; n < items.size(); n++) {
      assertThat(items.get(n).path("order").asInt()).isEqualTo(n + 1);
    }
    List<String> centerScoped = new ArrayList<>();
    items.forEach(
        i -> {
          if (i.path("centerScoped").asBoolean()) {
            centerScoped.add(i.path("code").asText());
          }
        });
    assertThat(centerScoped).containsExactly("MFC_SUPERVISOR", "MFC_WORKER");
    assertThat(items.get(0).path("name").asText()).isEqualTo("운영 관리자");
    assertThat(items.get(7).path("name").asText()).isEqualTo("센터 관리자");
    assertThat(items.get(11).path("name").asText()).isEqualTo("라이더 운영");
    assertThat(items.get(12).path("name").asText()).isEqualTo("배송 권역 승인자");
  }

  @Test
  void worksForStaffWithoutRolesAndRequiresToken() {
    assertThat(roles(mint(List.of())).status()).isEqualTo(200);
    assertThat(roles(null).status()).isEqualTo(401);
  }
}
