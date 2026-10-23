package com.pmart.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * contracts/events 의 실제 스키마(OrderConfirmed.v1)로 검증. jar 리소스 pmart-events/ 는 processResources 가 복사.
 */
class EventSchemaValidatorTest {

  private final EventEnvelopeCodec codec = EventEnvelopeCodec.withDefaultMapper();

  private EventEnvelope orderConfirmed(Map<String, Object> payload) {
    return new EventEnvelope(
        UUID.randomUUID(),
        "OrderConfirmed",
        1,
        "order-service",
        "Order",
        "ord-1",
        1,
        Instant.parse("2026-10-23T03:00:00Z"),
        "Asia/Seoul",
        "trace-1",
        null,
        null,
        null,
        EventEnvelope.EventSource.REAL,
        codec.mapper().valueToTree(payload));
  }

  private static Map<String, Object> validPayload() {
    return Map.of(
        "orderId", "ord-1",
        "orderVersion", 1,
        "accountId", "acc-1",
        "mfcId", "MFC-BSN-01",
        "lines",
            List.of(
                Map.of(
                    "orderLineId", "ol-1",
                    "sku", "E-00000001",
                    "qty", 2,
                    "unitsPerSku", 1,
                    "unitPrice", 5000,
                    "discountShare", 0)),
        "deliveryMode", "SINGLE",
        "outOfStockPreference", "CANCEL_OOS_ONLY",
        "reservationId", "res-1",
        "paymentAttemptId", "pay-1",
        "totalAmount", 14100);
  }

  @Test
  void schemaIsBundledAndValidPayloadPasses() {
    EventSchemaValidator v = new EventSchemaValidator(EventSchemaValidator.Mode.ENFORCE, codec);
    assertThat(v.hasSchema("OrderConfirmed", 1)).isTrue();
    assertThat(v.check(orderConfirmed(validPayload()))).isEmpty();
  }

  @Test
  void enforceRejectsMissingRequiredAndBadSku() {
    EventSchemaValidator v = new EventSchemaValidator(EventSchemaValidator.Mode.ENFORCE, codec);
    Map<String, Object> bad = new java.util.HashMap<>(validPayload());
    bad.remove("totalAmount");
    bad.put(
        "lines",
        List.of(
            Map.of(
                "orderLineId", "ol-1",
                "sku", "X-1",
                "qty", 0,
                "unitsPerSku", 1,
                "unitPrice", 5000,
                "discountShare", 0)));
    List<String> errors = v.check(orderConfirmed(bad));
    assertThat(errors).isNotEmpty();
    assertThat(String.join("\n", errors)).contains("totalAmount").contains("sku");
    assertThatThrownBy(() -> v.validate(orderConfirmed(bad)))
        .isInstanceOf(EventSchemaValidator.EventSchemaViolation.class);
  }

  @Test
  void warnModeDoesNotThrow() {
    EventSchemaValidator v = new EventSchemaValidator(EventSchemaValidator.Mode.WARN, codec);
    Map<String, Object> bad = new java.util.HashMap<>(validPayload());
    bad.remove("orderId");
    v.validate(orderConfirmed(bad)); // 로그만
  }

  @Test
  void unknownSchemaIsSkippedNotRejected() {
    EventSchemaValidator v = new EventSchemaValidator(EventSchemaValidator.Mode.ENFORCE, codec);
    EventEnvelope e =
        new EventEnvelope(
            UUID.randomUUID(),
            "NotAContractEvent",
            1,
            "x-service",
            "Thing",
            "1",
            0,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            EventEnvelope.EventSource.REAL,
            codec.mapper().createObjectNode());
    assertThat(v.hasSchema("NotAContractEvent", 1)).isFalse();
    assertThat(v.check(e)).isEmpty();
  }

  @Test
  void additionalPayloadFieldsAreAllowed() {
    EventSchemaValidator v = new EventSchemaValidator(EventSchemaValidator.Mode.ENFORCE, codec);
    Map<String, Object> extra = new java.util.HashMap<>(validPayload());
    extra.put("newFieldAddedLater", "ok");
    assertThat(v.check(orderConfirmed(extra))).isEmpty();
  }
}
