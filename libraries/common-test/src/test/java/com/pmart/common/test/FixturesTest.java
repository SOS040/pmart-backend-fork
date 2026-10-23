package com.pmart.common.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventSchemaValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FixturesTest {

  @Test
  void seoulClockIsUtcInstantOfSeoulWallTime() {
    Clock c = SeoulClock.fixedAt(LocalDate.of(2026, 10, 24), LocalTime.MIDNIGHT);
    assertThat(Instant.now(c)).isEqualTo(Instant.parse("2026-10-23T15:00:00Z"));
    assertThat(SeoulClock.fixedAt("2026-10-24T09:30:00").instant())
        .isEqualTo(Instant.parse("2026-10-24T00:30:00Z"));
  }

  @Test
  void envelopeFixtureProducesContractValidEvent() {
    EventEnvelope e =
        EventEnvelopeFixtures.envelope("OrderConfirmed")
            .from("order-service")
            .aggregate("Order", "ord-1", 1)
            .payload(
                Map.of(
                    "orderId", "ord-1",
                    "orderVersion", 1,
                    "accountId", "acc-1",
                    "mfcId", "MFC-BSN-01",
                    "lines",
                        List.of(
                            Map.of(
                                "orderLineId", "ol-1",
                                "sku", "E-00000001",
                                "qty", 1,
                                "unitsPerSku", 1,
                                "unitPrice", 15000,
                                "discountShare", 0)),
                    "deliveryMode", "SINGLE",
                    "outOfStockPreference", "CANCEL_ALL",
                    "reservationId", "r-1",
                    "paymentAttemptId", "p-1",
                    "totalAmount", 15100))
            .build();
    EventSchemaValidator v =
        new EventSchemaValidator(EventSchemaValidator.Mode.ENFORCE, EventEnvelopeFixtures.codec());
    assertThat(v.check(e)).isEmpty();
    assertThat(EventEnvelopeFixtures.envelope("SimulatedThing").simulated().build().source())
        .isEqualTo(EventEnvelope.EventSource.SIMULATED);
  }
}
