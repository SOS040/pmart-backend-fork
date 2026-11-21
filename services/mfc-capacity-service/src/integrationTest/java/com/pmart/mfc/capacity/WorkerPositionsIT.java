package com.pmart.mfc.capacity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 센터 도면 위 작업자 위치(0.34.0): 권한, 좌표 있는 작업자·없는 작업자(NO_POSITION), 실패한 원천. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.capacity.plan.mfc-fulfillment-base-url=${ful.url}",
      "pmart.capacity.plan.mfc-location-base-url=${ful.url}",
      "pmart.capacity.plan.mfc-inbound-base-url=${inb.url}"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "ful", baseUrlProperties = "ful.url"),
  @ConfigureWireMock(name = "inb", baseUrlProperties = "inb.url")
})
class WorkerPositionsIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-POS-1";
  static final String SUP = TestJwt.operator("pos-sup", List.of("MFC_SUPERVISOR"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("ful")
  WireMockServer ful;

  @InjectWireMock("inb")
  WireMockServer inb;

  record Res(int status, JsonNode body) {}

  Res get(String token) {
    ResponseEntity<String> e =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build()
            .get()
            .uri("/mfcs/" + MFC + "/worker-positions")
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  @Test
  void supervisorSeesPlacedUnplacedAndUnavailableSources() {
    Instant t1 = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    Instant t2 = t1.minus(10, ChronoUnit.MINUTES);
    ful.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/worker-last-locations"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":["
                            + "{\"workerId\":\"OP-w1\",\"locationCode\":\"A-01-01\",\"activityType\":\"PICK\",\"at\":\""
                            + t1
                            + "\"},"
                            + "{\"workerId\":\"OP-w2\",\"locationCode\":\"Z-99-99\",\"activityType\":\"PACK\",\"at\":\""
                            + t2
                            + "\"}]}")));
    inb.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/worker-last-locations"))
            .willReturn(aResponse().withStatus(503)));
    ful.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/location-distances"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"codes\":[\"A-01-01\"],\"meters\":[[0]],\"missing\":[\"Z-99-99\"],"
                            + "\"attributes\":[{\"locationCode\":\"A-01-01\",\"zone\":\"A\","
                            + "\"temperatureZone\":\"AMBIENT\",\"position\":{\"xM\":12.5,\"zM\":3.0}}]}")));

    // 권한: 작업자·다른 센터 감독자는 403
    assertThat(get(TestJwt.operator("pos-w", List.of("MFC_WORKER"), List.of(MFC))).status())
        .isEqualTo(403);
    assertThat(
            get(TestJwt.operator("pos-x", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER")))
                .status())
        .isEqualTo(403);

    Res r = get(SUP);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode b = r.body();
    assertThat(b.path("mfcId").asString()).isEqualTo(MFC);
    assertThat(b.path("items")).hasSize(1);
    JsonNode p = b.path("items").path(0);
    assertThat(p.path("workerId").asString()).isEqualTo("OP-w1");
    assertThat(p.path("xM").asDouble()).isEqualTo(12.5);
    assertThat(p.path("zM").asDouble()).isEqualTo(3.0);
    assertThat(p.path("source").asString()).isEqualTo("SCAN");
    assertThat(p.path("locationCode").asString()).isEqualTo("A-01-01");
    assertThat(p.path("activityType").asString()).isEqualTo("PICK");
    assertThat(b.path("unplaced")).hasSize(1);
    JsonNode u = b.path("unplaced").path(0);
    assertThat(u.path("workerId").asString()).isEqualTo("OP-w2");
    assertThat(u.path("locationCode").asString()).isEqualTo("Z-99-99");
    assertThat(u.path("reason").asString()).isEqualTo("NO_POSITION");
    assertThat(b.path("unavailableSources")).hasSize(1);
    assertThat(b.path("unavailableSources").path(0).asString()).isEqualTo("mfc-inbound-service");
  }
}
