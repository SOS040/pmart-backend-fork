package com.pmart.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * traceId: 실제 Netty 서버 + 트레이싱 켬(Zipkin 주소만 닿지 않게). 게이트웨이 자체 오류(401 Problem)에 traceId 가 실리는지, 들어온
 * traceparent 를 이어 받는지, 업스트림에 같은 traceId 로 전파하는지 확인한다. MOCK WebTestClient 는 서버 관측을 거치지 않아 여기서는 쓰지
 * 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.data.redis.url=redis://localhost:1",
      "pmart.gateway.rate-limit.enabled=false",
      // 내보내기를 끄면 Boot 가 전파기도 만들지 않는다(NoopTextMapPropagator) — 켜고 Zipkin 주소만 닿지 않게
      "management.tracing.export.zipkin.endpoint=http://localhost:1/api/v2/spans"
    })
@EnableWireMock(
    @ConfigureWireMock(
        name = "upstream",
        baseUrlProperties = {"pmart.gateway.upstream.storefront-service"}))
class TraceIdIT {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

  @LocalServerPort int port;

  @InjectWireMock("upstream")
  WireMockServer upstream;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void stubUpstream() {
    upstream.stubFor(
        any(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));
  }

  private HttpResponse<String> get(String path, String traceparent) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (traceparent != null) {
      b.header("traceparent", traceparent);
    }
    return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void unauthenticatedProblemCarriesNewTraceId() throws Exception {
    HttpResponse<String> res = get("/api/v1/carts/me", null);

    assertThat(res.statusCode()).isEqualTo(401);
    JsonNode body = json.readTree(res.body());
    assertThat(body.path("traceId").asString()).matches("[0-9a-f]{32}");
    assertThat(body.path("requestId").asString()).isNotBlank();
  }

  @Test
  void incomingTraceparentIsContinuedInProblem() throws Exception {
    HttpResponse<String> res = get("/api/v1/carts/me", TRACEPARENT);

    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(json.readTree(res.body()).path("traceId").asString()).isEqualTo(TRACE_ID);
  }

  @Test
  void upstreamReceivesSameTraceId() throws Exception {
    HttpResponse<String> res = get("/api/v1/storefront/home", TRACEPARENT);

    assertThat(res.statusCode()).isEqualTo(200);
    String sent = upstream.getAllServeEvents().getFirst().getRequest().getHeader("traceparent");
    // 같은 trace, 게이트웨이가 만든 자식 span(부모 span ID 가 클라이언트가 보낸 것과 다르다)
    assertThat(sent).startsWith("00-" + TRACE_ID + "-").doesNotContain("00f067aa0ba902b7");
  }
}
