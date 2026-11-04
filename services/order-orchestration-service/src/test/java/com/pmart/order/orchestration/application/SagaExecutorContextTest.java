package com.pmart.order.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** 작업 A: 사가 실행기가 호출 스레드의 MDC(correlationId)를 가상 스레드로 넘긴다. */
class SagaExecutorContextTest {

  @Test
  void executorCarriesCallerMdc() throws Exception {
    com.pmart.common.observability.MdcContextPropagation
        .register(); // 앱에서는 CommonObservabilityAutoConfiguration 이 한다
    try (ExecutorService executor = new OrchestrationConfig().sagaExecutor()) {
      MDC.put("correlationId", "SAGA-test");
      try {
        String seen = executor.submit(() -> MDC.get("correlationId")).get(5, TimeUnit.SECONDS);
        assertThat(seen).isEqualTo("SAGA-test");
      } finally {
        MDC.remove("correlationId");
      }
      String afterRemoval =
          executor.submit(() -> MDC.get("correlationId")).get(5, TimeUnit.SECONDS);
      assertThat(afterRemoval).isNull();
    }
  }
}
