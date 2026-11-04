package com.pmart.common.observability;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * 일정 구간 동안 correlationId 를 정해 둔다 — MDC({@value CorrelationIdFilter#MDC_KEY})와 Micrometer baggage(원격
 * 필드) 둘 다. 끝나면 이전 값으로 되돌린다.
 *
 * <p>요청이 아닌 곳에서 사가·사건 단위로 문맥을 여는 데 쓴다(trace-correlation-plan.md 작업 B·C): 사가 진행(sagaId), 이벤트 소비(봉투의
 * correlationId). 이 구간에서 나가는 HTTP 호출은 {@code X-Correlation-Id}·baggage 로, 발행하는 사건은 봉투의
 * correlationId 로 같은 값을 잇는다.
 *
 * <pre>{@code
 * try (var c = CorrelationScope.open(tracer, sagaId)) { ... }
 * }</pre>
 */
public final class CorrelationScope implements AutoCloseable {

  /** baggage 원격 필드 이름(ObservabilityDefaultsPostProcessor 의 remote-fields 와 같다). */
  public static final String BAGGAGE = "correlationId";

  private final @Nullable String previous;
  private final @Nullable BaggageInScope baggage;

  private CorrelationScope(@Nullable String previous, @Nullable BaggageInScope baggage) {
    this.previous = previous;
    this.baggage = baggage;
  }

  /**
   * @param tracer 없으면(추적 꺼짐·단위 시험) MDC 만
   * @param correlationId 비어 있으면 아무것도 바꾸지 않는다
   */
  public static CorrelationScope open(@Nullable Tracer tracer, @Nullable String correlationId) {
    String previous = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (correlationId == null || correlationId.isBlank()) {
      return new CorrelationScope(previous, null);
    }
    MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
    BaggageInScope baggage = null;
    if (tracer != null) {
      try {
        baggage = tracer.createBaggageInScope(BAGGAGE, correlationId);
      } catch (RuntimeException e) {
        baggage = null; // baggage 를 못 열어도 MDC(→ X-Correlation-Id·봉투)는 이어진다
      }
    }
    return new CorrelationScope(previous, baggage);
  }

  @Override
  public void close() {
    if (baggage != null) {
      baggage.close();
    }
    if (previous == null) {
      MDC.remove(CorrelationIdFilter.MDC_KEY);
    } else {
      MDC.put(CorrelationIdFilter.MDC_KEY, previous);
    }
  }
}
