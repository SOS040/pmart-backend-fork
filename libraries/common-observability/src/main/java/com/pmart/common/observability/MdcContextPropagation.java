package com.pmart.common.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MDC 의 correlationId·sourceSystem 을 context-propagation 에 등록한다. 등록하면 {@code
 * ContextExecutorService}·{@code ContextSnapshot} 으로 감싼 실행기(사가·환불·조회 가상 스레드)가 trace 뿐 아니라 이 MDC 값도
 * 넘긴다(trace-correlation-plan.md 작업 A). 다른 MDC 키는 넘기지 않는다(선택 등록).
 */
public final class MdcContextPropagation {

  private static final AtomicBoolean REGISTERED = new AtomicBoolean();

  private MdcContextPropagation() {}

  /** 여러 번 불러도 한 번만 등록한다. */
  public static void register() {
    if (REGISTERED.compareAndSet(false, true)) {
      ContextRegistry.getInstance()
          .registerThreadLocalAccessor(
              new Slf4jThreadLocalAccessor(
                  CorrelationIdFilter.MDC_KEY, CorrelationIdFilter.SOURCE_SYSTEM_MDC_KEY));
    }
  }
}
