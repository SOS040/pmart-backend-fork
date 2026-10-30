package com.pmart.delivery.serviceability;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 권역 매핑: 운영자 초안 → ApprovalDecided(SERVICEABILITY, Kafka) → 시험 주소 검증 →
 * 발효(ServiceabilityVersionApplied) → 좌표 판정. 실제 PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class MappingVersionFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  static final String OPS = TestJwt.operator("ops-1", List.of("OPS"), List.of());
  static final String CUSTOMER = TestJwt.customer("CU-1");

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

  Res post(String token, String path, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "it");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static List<List<Double>> box(double x0, double y0, double x1, double y1) {
    return List.of(
        List.of(x0, y0), List.of(x1, y0), List.of(x1, y1), List.of(x0, y1), List.of(x0, y0));
  }

  static Map<String, Object> zone(
      String id, String mfc, double x0, double y0, double x1, double y1) {
    return Map.of(
        "zoneId", id,
        "mfcId", mfc,
        "name", id,
        "geometry", Map.of("type", "Polygon", "coordinates", List.of(box(x0, y0, x1, y1))));
  }

  void decide(JsonNode approvalRequest, String decision, String hashOverride) {
    String approvalId = "AP-" + UUID.randomUUID();
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put(
        "draftHash",
        hashOverride != null
            ? hashOverride
            : approvalRequest
                .path("lines")
                .path(0)
                .path("attributes")
                .path("draftHash")
                .asString());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("type", "SERVICEABILITY");
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType", "ServiceabilityVersion",
            "targetId", approvalRequest.path("targetRef").asString(),
            "targetService", "delivery-serviceability-service"));
    payload.put(
        "lines", List.of(Map.of("lineId", "l1", "decision", decision, "attributes", attrs)));
    payload.put("approverId", "lead-1");
    payload.put("dossierVersion", 2);
    String json =
        EventEnvelopeFixtures.envelope("ApprovalDecided")
            .from("approval-service")
            .aggregate("Approval", approvalId, 2)
            .payload(payload)
            .buildJson();
    kafka.send("platform.approval.v1", approvalId, json).join();
  }

  String awaitState(int version, String expected) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    String s = null;
    while (Instant.now().isBefore(until)) {
      s =
          jdbc.queryForObject(
              "SELECT state FROM mapping_version WHERE version = ?", String.class, version);
      if (expected.equals(s)) {
        return s;
      }
      Thread.sleep(200);
    }
    return s;
  }

  Res check(Double lat, Double lng) {
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("roadAddress", "서울 서초구 어딘가");
    if (lat != null) {
      address.put("lat", lat);
      address.put("lng", lng);
    }
    return post(
        CUSTOMER, "/serviceability-checks", Map.of("address", address, "purpose", "ADDRESS_BOOK"));
  }

  @Test
  void draftApprovalActivationAndChecks() throws Exception {
    // 발효 버전 없음 → CONFIG_REQUIRED (시험 순서와 무관하게 시작 시점에만 확인)
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM mapping_version WHERE state = 'ACTIVE'", Integer.class)
        == 0) {
      assertThat(check(37.50, 127.02).status()).isEqualTo(422);
    }
    // 고객은 초안을 만들 수 없다
    Map<String, Object> draft =
        Map.of(
            "zones", List.of(zone("Z-SEOCHO", "MFC-BSN-01", 127.00, 37.48, 127.04, 37.52)),
            "changeSummary", "서초 1개 권역",
            "testAddresses",
                List.of(
                    Map.of(
                        "label",
                        "안",
                        "lat",
                        37.50,
                        "lng",
                        127.02,
                        "expectedResult",
                        "SERVICEABLE",
                        "expectedMfcId",
                        "MFC-BSN-01"),
                    Map.of(
                        "label",
                        "밖",
                        "lat",
                        37.60,
                        "lng",
                        127.02,
                        "expectedResult",
                        "OUT_OF_AREA")));
    assertThat(post(CUSTOMER, "/serviceability-versions", draft).status()).isEqualTo(403);

    Res created = post(OPS, "/serviceability-versions", draft);
    assertThat(created.status()).isEqualTo(201);
    int version = created.body().path("version").path("version").asInt();
    JsonNode approval = created.body().path("approvalRequest");
    assertThat(approval.path("type").asString()).isEqualTo("SERVICEABILITY");
    assertThat(approval.path("targetRef").asString()).isEqualTo("serviceabilityVersion:" + version);

    // 승인 범위 불일치(다른 해시) → 발효하지 않음
    Res other = post(OPS, "/serviceability-versions", draft);
    int otherVersion = other.body().path("version").path("version").asInt();
    decide(other.body().path("approvalRequest"), "APPROVED", "deadbeef");
    assertThat(awaitState(otherVersion, "REJECTED")).isEqualTo("REJECTED");

    // 승인 → 시험 주소 통과 → 발효
    decide(approval, "APPROVED", null);
    assertThat(awaitState(version, "ACTIVE")).isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ServiceabilityVersionApplied'"
                    + " AND topic = 'delivery.serviceability.v1'",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
    assertThat(
            get(OPS, "/serviceability-versions/" + version)
                .body()
                .path("testAddressesVerified")
                .asInt())
        .isEqualTo(2);

    // 판정
    Res inside = check(37.50, 127.02);
    assertThat(inside.status()).isEqualTo(200);
    assertThat(inside.body().path("result").asString()).isEqualTo("SERVICEABLE");
    assertThat(inside.body().path("mfcId").asString()).isEqualTo("MFC-BSN-01");
    assertThat(inside.body().path("mappingVersion").asInt()).isEqualTo(version);
    assertThat(check(37.60, 127.02).body().path("result").asString()).isEqualTo("OUT_OF_AREA");
    // 비회원(토큰 없음)도 판정 가능(계약 security: [])
    var anonymous =
        http.post()
            .uri("/serviceability-checks")
            .headers(
                h -> {
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "address",
                    Map.of("roadAddress", "서울", "lat", 37.50, "lng", 127.02),
                    "purpose",
                    "ADDRESS_BOOK"))
            .retrieve()
            .toEntity(String.class);
    assertThat(anonymous.getStatusCode().value()).isEqualTo(200);
    assertThat(mapper.readTree(anonymous.getBody()).path("result").asString())
        .isEqualTo("SERVICEABLE");
    Res noGeo = check(null, null);
    assertThat(noGeo.body().path("result").asString()).isEqualTo("NEEDS_INPUT");
    assertThat(noGeo.body().path("missingFields").path(0).asString()).isEqualTo("geo");
    // 재조회
    assertThat(
            get(CUSTOMER, "/serviceability-checks/" + inside.body().path("checkId").asString())
                .body()
                .path("result")
                .asString())
        .isEqualTo("SERVICEABLE");

    // 시험 주소 기대와 다르면 발효하지 않는다(기존 발효 버전 유지)
    Map<String, Object> wrong =
        Map.of(
            "zones", List.of(zone("Z-SEOCHO", "MFC-BSN-01", 127.00, 37.48, 127.04, 37.52)),
            "testAddresses",
                List.of(Map.of("lat", 37.60, "lng", 127.02, "expectedResult", "SERVICEABLE")));
    Res w = post(OPS, "/serviceability-versions", wrong);
    int wv = w.body().path("version").path("version").asInt();
    decide(w.body().path("approvalRequest"), "APPROVED", null);
    assertThat(awaitState(wv, "REJECTED")).isEqualTo("REJECTED");
    assertThat(check(37.50, 127.02).body().path("mappingVersion").asInt()).isEqualTo(version);

    // 잘못된 경계(닫히지 않은 링)는 초안 단계에서 거절
    Map<String, Object> bad =
        Map.of(
            "zones",
            List.of(
                Map.of(
                    "zoneId", "Z-BAD",
                    "mfcId", "MFC-BSN-01",
                    "geometry",
                        Map.of(
                            "type",
                            "Polygon",
                            "coordinates",
                            List.of(
                                List.of(
                                    List.of(127.0, 37.0),
                                    List.of(127.1, 37.0),
                                    List.of(127.1, 37.1),
                                    List.of(127.0, 37.1)))))));
    assertThat(post(OPS, "/serviceability-versions", bad).status()).isEqualTo(400);
  }
}
