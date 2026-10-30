package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** 정책값(창당 수용 단위) 미설정 → Hold·스냅샷 모두 422 CONFIG_REQUIRED (0·무제한 아님). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour="
    })
@Import(TestJwtConfiguration.class)
class CapacityNotSetIT extends PostgresKafkaContainerSupport {

  static final String SERVICE = TestJwt.service("order-orchestration-service");
  static final String WINDOW = "2026-11-02T01:00:00Z/2026-11-02T02:00:00Z";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @Test
  void notSetIsConfigRequired() {
    RestClient http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/internal")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    var hold =
        http.post()
            .uri("/capacity-holds")
            .headers(
                h -> {
                  h.setBearerAuth(SERVICE);
                  h.set("Idempotency-Key", "k-notset");
                  h.set("X-Source-System", "order-orchestration-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapper.writeValueAsString(
                    Map.of(
                        "orderId",
                        "O-1",
                        "mfcId",
                        "MFC-1",
                        "window",
                        WINDOW,
                        "demand",
                        Map.of("lineCount", 1))))
            .retrieve()
            .toEntity(String.class);
    assertThat(hold.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(hold.getBody()).path("code").asString())
        .isEqualTo("CONFIG_REQUIRED");

    var snap =
        http.get()
            .uri("/capacity-snapshots?mfcId=MFC-1&window={w}", WINDOW)
            .headers(h -> h.setBearerAuth(SERVICE))
            .retrieve()
            .toEntity(String.class);
    assertThat(snap.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(snap.getBody()).path("code").asString())
        .isEqualTo("CONFIG_REQUIRED");
  }
}
