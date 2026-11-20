package com.pmart.customer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 찜(0.13.0, ADR-0081 — product-content 는 WireMock): 찜하기 멱등·빼기 멱등·한도(시험 값 2)·ID 전체·목록 표시(판매 중·판매
 * 종료·카드 없음 UNKNOWN)· 카드 조회 실패여도 목록은 나옴·비회원 403·탈퇴 신청 뒤 409.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.customer.wishlist.max-items=2"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(name = "content", baseUrlProperties = "pmart.customer.content-base-url"))
class WishlistFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("content")
  WireMockServer content;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    content.resetAll();
  }

  record Res(int status, JsonNode body) {}

  Res call(String method, String token, String path) {
    var e =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                })
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  @Test
  void addRemoveLimitAndList() {
    String id = "CU-WL-" + UUID.randomUUID();
    String token = TestJwt.customer(id);

    Res first = call("PUT", token, "/me/wishlist-items/P-APPLE");
    assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
    assertThat(first.body().path("total").asInt()).isEqualTo(1);
    String addedAt = first.body().path("addedAt").asString();
    // 멱등 — 같은 상품을 다시 찜하면 그대로(처음 시각)
    Res again = call("PUT", token, "/me/wishlist-items/P-APPLE");
    assertThat(again.body().path("addedAt").asString()).isEqualTo(addedAt);
    assertThat(again.body().path("total").asInt()).isEqualTo(1);
    assertThat(call("PUT", token, "/me/wishlist-items/P-STOP").status()).isEqualTo(200);
    // 한도(시험 2개)
    Res over = call("PUT", token, "/me/wishlist-items/P-THIRD");
    assertThat(over.status()).isEqualTo(422);
    assertThat(over.body().path("code").asString()).isEqualTo("WISHLIST_LIMIT_EXCEEDED");
    // 형식 아닌 ID
    assertThat(call("PUT", token, "/me/wishlist-items/bad id!").status()).isIn(400, 404);

    // ID 전체(최근 순)
    JsonNode ids = call("GET", token, "/me/wishlist-product-ids").body();
    assertThat(ids.path("productIds").toString()).isEqualTo("[\"P-STOP\",\"P-APPLE\"]");

    // 목록 — P-APPLE 판매 중, P-STOP 판매 중지
    content.stubFor(
        get(urlPathEqualTo("/api/v1/product-contents"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":["
                            + "{\"productId\":\"P-APPLE\",\"name\":\"사과\",\"mainImage\":{\"url\":\"/api/v1/content-images/i1\"},"
                            + "\"options\":[{\"saleState\":\"ON_SALE\",\"price\":{\"basePrice\":5900}}]},"
                            + "{\"productId\":\"P-STOP\",\"name\":\"단종\",\"options\":[{\"saleState\":\"SALE_STOPPED\"}]}"
                            + "]}")));
    JsonNode list = call("GET", token, "/me/wishlist-items").body();
    assertThat(list.path("total").asInt()).isEqualTo(2);
    assertThat(list.path("limit").asInt()).isEqualTo(2);
    assertThat(list.at("/items/0/productId").asString()).isEqualTo("P-STOP");
    assertThat(list.at("/items/0/saleState").asString()).isEqualTo("ENDED");
    assertThat(list.at("/items/1/name").asString()).isEqualTo("사과");
    assertThat(list.at("/items/1/price").asLong()).isEqualTo(5900);
    assertThat(list.at("/items/1/imageUrl").asString()).isEqualTo("/api/v1/content-images/i1");
    assertThat(list.at("/items/1/saleState").asString()).isEqualTo("ON_SALE");

    // 카드 조회 실패 — 목록은 나오고 표시 값만 비움
    content.resetAll();
    content.stubFor(
        get(urlPathEqualTo("/api/v1/product-contents")).willReturn(aResponse().withStatus(503)));
    JsonNode degraded = call("GET", token, "/me/wishlist-items").body();
    assertThat(degraded.path("items")).hasSize(2);
    assertThat(degraded.at("/items/0/saleState").asString()).isEqualTo("UNKNOWN");
    assertThat(
            degraded.at("/items/0/name").isNull() || degraded.at("/items/0/name").isMissingNode())
        .isTrue();

    // 빼기 — 멱등(없어도 204)
    assertThat(call("DELETE", token, "/me/wishlist-items/P-STOP").status()).isEqualTo(204);
    assertThat(call("DELETE", token, "/me/wishlist-items/P-STOP").status()).isEqualTo(204);
    assertThat(call("GET", token, "/me/wishlist-product-ids").body().path("total").asInt())
        .isEqualTo(1);

    // 비회원 토큰 403
    assertThat(
            call(
                    "GET",
                    TestJwt.token(
                        "GS-wl", "GUEST", java.util.List.of(), java.util.List.of(), "customer-app"),
                    "/me/wishlist-items")
                .status())
        .isEqualTo(403);

    // 탈퇴 신청 이후 찜하기 409
    jdbc.update("UPDATE customer SET status = 'WITHDRAW_PENDING' WHERE account_id = ?", id);
    assertThat(call("PUT", token, "/me/wishlist-items/P-NEW").status()).isEqualTo(409);
  }
}
