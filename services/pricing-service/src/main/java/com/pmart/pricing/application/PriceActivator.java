package com.pmart.pricing.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.pricing.domain.PriceVersion;
import com.pmart.pricing.infrastructure.PriceStore;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가격 버전 발효: 상태를 EFFECTIVE 로, 앞선 버전을 SUPERSEDED 로 바꾸고 PriceVersionEffective 를 같은 트랜잭션에서 Outbox 에 쓴다.
 * 효력 시각이 이미 된 버전은 반영 트랜잭션에서 바로, 예약 버전은 주기 작업이 발효한다(여러 인스턴스는 SKIP LOCKED 로 나눠 잡음).
 */
@Service
public class PriceActivator {

  /** 계약 PriceVersionEffective.v1 의 토픽. 집합 이름(Price)이 아니라 서비스 이름을 쓴다. */
  public static final String TOPIC = "commerce.pricing.v1";

  private static final Logger log = LoggerFactory.getLogger(PriceActivator.class);
  private static final int BATCH = 100;

  private final PriceStore store;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PriceActivator(
      PriceStore store,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.codec = codec;
    this.tx = tx;
    this.clock = clock;
  }

  /** 호출자 트랜잭션 안에서 한 버전을 발효한다. */
  public void activate(PriceVersion v) {
    Instant at = clock.instant();
    store.markEffective(v, at);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sku", v.sku());
    payload.put("price", v.basePrice());
    if (v.previousPrice() != null) {
      payload.put("previousPrice", v.previousPrice());
    }
    payload.put("priceVersion", v.version());
    payload.put("effectiveAt", v.effectiveFrom().toString());
    payload.put("approvalRef", Map.of("approvalId", v.approvalId()));
    EventEnvelope envelope =
        codec.create(
            events.sourceSystem(),
            "PriceVersionEffective",
            1,
            "Price",
            v.sku(),
            v.version(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(envelope, TOPIC);
    log.info("price {} v{} effective at {}", v.sku(), v.version(), v.effectiveFrom());
  }

  /** 효력 시각이 된 예약 버전 발효. */
  @Scheduled(fixedDelayString = "${pmart.pricing.activation-interval:PT10S}")
  public void activateDue() {
    int done;
    do {
      Integer n =
          tx.execute(
              status -> {
                List<PriceVersion> due = store.lockDue(clock.instant(), BATCH);
                due.forEach(this::activate);
                return due.size();
              });
      done = n == null ? 0 : n;
    } while (done == BATCH);
  }
}
