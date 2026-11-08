package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 오전 입고 수령창·하차면 예약: 창 조회, 창 밖·1회 상한·하차면 소진 거절, 같은 운행 하나, 취소 후 하차면 재사용. 시험값 07~10시·150·300·2면. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.capacity.receiving.window-start=07:00",
      "pmart.capacity.receiving.window-end=10:00",
      "pmart.capacity.receiving.max-load-units-per-arrival=150",
      "pmart.capacity.receiving.total-load-units=300",
      "pmart.capacity.receiving.bay-count=2"
    })
@Import(TestJwtConfiguration.class)
class ReceivingIT extends PostgresKafkaContainerSupport {

  static final String SERVICE = TestJwt.service("transport-service");
  static final String MFC = "MFC-RCV-1";
  // 2026-11-10 서울 07:00 = 전날 22:00Z
  static final String IN_WINDOW = "2026-11-09T22:30:00Z/2026-11-09T23:00:00Z";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/internal")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  Res reserve(String trip, String window, int units) {
    var e =
        http.post()
            .uri("/unloading-bay-reservations")
            .headers(
                h -> {
                  h.setBearerAuth(SERVICE);
                  h.set("Idempotency-Key", "k-" + UUID.randomUUID());
                  h.set("X-Source-System", "transport-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapper.writeValueAsString(
                    Map.of(
                        "mfcId", MFC,
                        "tripId", trip,
                        "trfIds", java.util.List.of("TRF-" + trip),
                        "window", window,
                        "vehicleType", "TRUCK_1T",
                        "loadUnits", units)))
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res get(String path) {
    var e =
        http.get()
            .uri(path)
            .headers(h -> h.setBearerAuth(SERVICE))
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res cancel(String id, @Nullable Integer version) {
    var e =
        http.delete()
            .uri("/unloading-bay-reservations/" + id)
            .headers(
                h -> {
                  h.setBearerAuth(SERVICE);
                  h.set("Idempotency-Key", "k-" + UUID.randomUUID());
                  h.set("X-Source-System", "transport-service");
                  if (version != null) {
                    h.set("X-Expected-Version", version.toString());
                  }
                })
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  JsonNode read(@Nullable String s) {
    return s == null || s.isBlank() ? mapper.createObjectNode() : mapper.readTree(s);
  }

  @Test
  void windowAndBays() {
    var cap = get("/receiving-capabilities?mfcId=" + MFC + "&date=2026-11-10");
    assertThat(cap.status()).isEqualTo(200);
    var w = cap.body().get("windows").get(0);
    assertThat(w.get("start").asText()).startsWith("2026-11-09T22:00");
    assertThat(w.get("end").asText()).startsWith("2026-11-10T01:00");
    assertThat(w.get("maxLoadUnitsPerArrival").asInt()).isEqualTo(150);
    assertThat(w.get("reservedLoadUnits").asInt()).isZero();
    assertThat(w.get("state").asText()).isEqualTo("OPEN");

    // 창 밖(서울 11시)
    var outside = reserve("T-0", "2026-11-10T02:00:00Z/2026-11-10T02:30:00Z", 10);
    assertThat(outside.status()).isEqualTo(422);
    assertThat(outside.body().get("code").asText()).isEqualTo("OUTSIDE_RECEIVING_WINDOW");
    // 1회 상한
    var big = reserve("T-0", IN_WINDOW, 151);
    assertThat(big.status()).isEqualTo(422);
    assertThat(big.body().get("code").asText()).isEqualTo("PER_ARRIVAL_LIMIT_EXCEEDED");

    var a = reserve("T-1", IN_WINDOW, 100);
    assertThat(a.status()).isEqualTo(201);
    String aId = a.body().get("resultId").asText();
    var again = reserve("T-1", IN_WINDOW, 100);
    assertThat(again.body().get("resultId").asText()).isEqualTo(aId); // 같은 운행 하나
    var b = reserve("T-2", IN_WINDOW, 100);
    assertThat(b.status()).isEqualTo(201);
    var aRow = get("/unloading-bay-reservations/" + aId);
    var bRow = get("/unloading-bay-reservations/" + b.body().get("resultId").asText());
    assertThat(aRow.body().get("bayCode").asText()).isEqualTo("BAY-1");
    assertThat(bRow.body().get("bayCode").asText()).isEqualTo("BAY-2");

    // 하차면 2면 소진
    var c = reserve("T-3", IN_WINDOW, 50);
    assertThat(c.status()).isEqualTo(422);
    assertThat(c.body().get("code").asText()).isEqualTo("NO_UNLOADING_BAY");
    assertThat(
            get("/receiving-capabilities?mfcId=" + MFC + "&date=2026-11-10")
                .body()
                .get("windows")
                .get(0)
                .get("state")
                .asText())
        .isEqualTo("FULL");

    // 버전 충돌 → 409, 맞는 버전 → 202, 하차면 BAY-1 재사용
    assertThat(cancel(aId, 9).status()).isEqualTo(409);
    assertThat(cancel(aId, 1).status()).isEqualTo(202);
    assertThat(get("/unloading-bay-reservations/" + aId).body().get("state").asText())
        .isEqualTo("CANCELLED");
    var d = reserve("T-3", IN_WINDOW, 150);
    assertThat(d.status()).isEqualTo(201);
    assertThat(
            get("/unloading-bay-reservations/" + d.body().get("resultId").asText())
                .body()
                .get("bayCode")
                .asText())
        .isEqualTo("BAY-1");
    // 하루 상한 300: T-2 취소 뒤 150 + 150 = 300 → 다음 10 은 하차면보다 먼저 하루 상한으로 거절
    assertThat(cancel(b.body().get("resultId").asText(), 1).status()).isEqualTo(202);
    var e = reserve("T-4", IN_WINDOW, 150);
    assertThat(e.status()).isEqualTo(201);
    var f = reserve("T-5", IN_WINDOW, 10);
    assertThat(f.status()).isEqualTo(422);
    assertThat(f.body().get("code").asText()).isEqualTo("RECEIVING_WINDOW_FULL");
  }

  @Test
  void unknownReservationIs404() {
    assertThat(get("/unloading-bay-reservations/BAY-none").status()).isEqualTo(404);
  }
}
