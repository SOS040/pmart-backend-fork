package com.pmart.mfc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.ObjectMapper;

/**
 * 재고 변동 → 자동 보충 즉시 점검(2026-11-08 남은 일 15): mfc.inventory.v1 의 InventoryExecutionApplied 가 오면 1분 주기를
 * 기다리지 않고 점검해 이동 지시를 남긴다. 상관없는 사건(InventoryHeld)은 점검하지 않는다. 주기 점검은 1시간으로 밀어 섞이지 않게.
 */
@SpringBootTest(
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.post-interval=PT1H",
      "pmart.inbound.replenishment-enabled=true",
      "pmart.inbound.replenishment-interval=PT1H",
      "pmart.inbound.replenishment-trigger-delay=PT1S"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class ReplenishmentTriggerIT extends PostgresKafkaContainerSupport {

  static final String EVAL = "/api/v1/internal/replenishment-evaluations";

  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("inventory")
  WireMockServer inv;

  void send(String type) {
    String id = "EXE-" + UUID.randomUUID();
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from("mfc-inventory-service")
            .aggregate("InventoryExecution", id, 1)
            .payload(
                Map.of(
                    "requestId", id,
                    "executionType", "PICK",
                    "mfcId", "MFC-TRG-1",
                    "result", "SUCCESS"))
            .buildJson();
    kafka.send("mfc.inventory.v1", id, json).join();
  }

  int moveOrders() {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM move_order WHERE move_id = 'RPL-TRG-1'", Integer.class);
    return n == null ? 0 : n;
  }

  @Test
  void stockChangeTriggersReplenishmentCheckSoon() throws Exception {
    Map<String, Object> mv = new LinkedHashMap<>();
    mv.put("moveId", "RPL-TRG-1");
    mv.put("kind", "REPLENISH");
    mv.put("mfcId", "MFC-TRG-1");
    mv.put("sku", "E-00000011");
    mv.put("lotId", "LOT-T");
    mv.put("fromLocation", "A-S1-05-1-01");
    mv.put("toLocation", "A-S9-01-1-01");
    mv.put("qty", 6);
    mv.put("pickedQty", 0);
    mv.put("droppedQty", 0);
    mv.put("state", "ACTIVE");
    inv.stubFor(
        WireMock.post(urlPathEqualTo(EVAL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of("created", 1, "moves", List.of(mv), "skipped", List.of())))));

    // 구독자가 붙기 전에 보낸 사건은 놓칠 수 있어 되풀이해 보낸다(점검은 멱등)
    long until = System.currentTimeMillis() + 60_000;
    while (moveOrders() == 0 && System.currentTimeMillis() < until) {
      send("InventoryExecutionApplied");
      Thread.sleep(2_000);
    }
    assertThat(moveOrders()).isEqualTo(1);

    // 상관없는 사건은 점검 안 함
    Thread.sleep(2_000); // 위에서 남은 표시가 소진되게
    inv.resetRequests();
    send("InventoryHeld");
    Thread.sleep(4_000);
    inv.verify(0, postRequestedFor(urlPathEqualTo(EVAL)));
  }
}
