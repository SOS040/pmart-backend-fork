package com.pmart.delivery.capacity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 예약 슬롯: center-master 운영 구간(서비스 토큰) × 정원 정책값. center-master·auth 는 WireMock. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${pmart.capacity.center-master-base-url}/api/v1/internal/service-tokens",
      "pmart.capacity.slot-capacity=20"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "center",
        baseUrlProperties = "pmart.capacity.center-master-base-url"))
class SlotsIT extends PostgresKafkaContainerSupport {

  static final String SERVICE_TOKEN = TestJwt.service("delivery-capacity-service");
  static final String GUEST = TestJwt.token("GST-1", "GUEST", List.of(), List.of(), "it");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("center")
  WireMockServer center;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    center.resetAll();
    center.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                json(
                    Map.of(
                        "access_token",
                        SERVICE_TOKEN,
                        "token_type",
                        "Bearer",
                        "expires_in",
                        300))));
    center.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/MFC-1/calendar"))
            .willReturn(
                json(
                    Map.of(
                        "centerId",
                        "MFC-1",
                        "open",
                        true,
                        "calendarVersion",
                        3,
                        "ranges",
                        List.of(Map.of("start", "08:00", "end", "24:00"))))));
    center.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/MFC-NONE/calendar"))
            .willReturn(aResponse().withStatus(404)));
    center.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/MFC-NOTABLE/calendar"))
            .willReturn(aResponse().withStatus(422)));
  }

  com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(Object body) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  record Res(int status, JsonNode body) {}

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  @Test
  void slotsFromCalendarAndCapacity() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    String tomorrow = today.plusDays(1).toString();
    Res r = get(TestJwt.customer("CU-1"), "/slots?mfcId=MFC-1&date=" + tomorrow);
    assertThat(r.status()).isEqualTo(200);
    JsonNode slots = r.body().path("slots");
    assertThat(slots).hasSize(16);
    assertThat(slots.path(0).path("window").path("start").asString()).isEqualTo("08:00");
    assertThat(slots.path(15).path("window").path("end").asString()).isEqualTo("24:00");
    assertThat(slots.path(0).path("availability").asString()).isEqualTo("AVAILABLE");
    assertThat(slots.path(0).path("capacity").path("state").asString()).isEqualTo("VALUE");
    assertThat(slots.path(0).path("capacity").path("value").asInt()).isEqualTo(20);
    assertThat(slots.path(0).path("remaining").asInt()).isEqualTo(20);
    assertThat(r.body().path("asOf").path("sourceVersion").asInt()).isEqualTo(3);
    // center-master 는 서비스 토큰으로(고객 토큰 전파 아님)
    center.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/internal/centers/MFC-1/calendar"))
            .withHeader("Authorization", equalTo("Bearer " + SERVICE_TOKEN)));

    // 비회원도 조회, 오늘 지난 시간대는 CLOSED
    Res guest = get(GUEST, "/slots?mfcId=MFC-1&date=" + today);
    assertThat(guest.status()).isEqualTo(200);
    // 오늘·내일만
    assertThat(get(GUEST, "/slots?mfcId=MFC-1&date=" + today.plusDays(2)).status()).isEqualTo(400);
    // 센터 없음·시간표 미승인
    assertThat(get(GUEST, "/slots?mfcId=MFC-NONE&date=" + tomorrow).status()).isEqualTo(404);
    Res noTable = get(GUEST, "/slots?mfcId=MFC-NOTABLE&date=" + tomorrow);
    assertThat(noTable.status()).isEqualTo(422);
    assertThat(noTable.body().path("code").asString()).isEqualTo("CONFIG_REQUIRED");
  }
}
