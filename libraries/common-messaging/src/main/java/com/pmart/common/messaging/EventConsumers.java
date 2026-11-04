package com.pmart.common.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.MDC;

/**
 * 업무 이벤트 소비 함수 공장(ADR-0035). 바인더가 넘긴 byte[] 를 봉투로 풀고 {@link InboxGuard} 안에서 처리기를 부른다 — 이미 받은 이벤트(같은
 * sourceSystem·eventId)는 건너뛴다. 처리기가 예외를 던지면 inbox 기록도 롤백되고 바인더가 재시도 → DLQ 로 넘긴다.
 *
 * <pre>{@code
 * @Bean PmartEventConsumer orderEvents(EventConsumers c, ClaimService claims) {
 *   return c.of(claims::onOrderEvent);
 * }
 * }</pre>
 */
public final class EventConsumers {

  /** common-observability CorrelationIdFilter.MDC_KEY 와 같다(의존성 없이). */
  static final String CORRELATION_MDC = "correlationId";

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;

  public EventConsumers(EventEnvelopeCodec codec, InboxGuard inbox) {
    this.codec = Objects.requireNonNull(codec);
    this.inbox = Objects.requireNonNull(inbox);
  }

  public PmartEventConsumer of(Consumer<EventEnvelope> handler) {
    return of(envelope -> true, handler);
  }

  /**
   * {@code accept} 가 거짓인 사건은 inbox 에 남기지 않고 건너뛴다(그 토픽의 다른 사건을 이 서비스가 다루지 않을 때). 참이면 {@link
   * #of(Consumer)} 와 같다.
   */
  public PmartEventConsumer of(Predicate<EventEnvelope> accept, Consumer<EventEnvelope> handler) {
    Objects.requireNonNull(accept);
    Objects.requireNonNull(handler);
    return new PmartEventConsumer(
        message -> {
          EventEnvelope envelope =
              codec.decode(new String(message.getPayload(), StandardCharsets.UTF_8));
          if (accept.test(envelope)) {
            // 작업 B: 처리 동안 correlationId = 봉투 값 → 이 반응이 내는 사건·HTTP 호출이 같은 사가·사건 ID 를 잇는다.
            // traceId 는 바인더 observation(enable-observation)이 traceparent 헤더로 이어 MDC 에 넣는다.
            String previous = MDC.get(CORRELATION_MDC);
            String correlation = envelope.correlationId();
            if (correlation != null && !correlation.isBlank()) {
              MDC.put(CORRELATION_MDC, correlation);
            }
            try {
              inbox.process(envelope, () -> handler.accept(envelope));
            } finally {
              if (previous == null) {
                MDC.remove(CORRELATION_MDC);
              } else {
                MDC.put(CORRELATION_MDC, previous);
              }
            }
          }
        });
  }
}
