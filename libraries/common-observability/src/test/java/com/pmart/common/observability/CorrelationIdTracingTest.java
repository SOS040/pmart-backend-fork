package com.pmart.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실제 추적 환경(Micrometer Tracing + OTel, 서버 관측 필터)에서 로그 MDC correlationId 가 요청 동안 유지되는가.
 *
 * <p>2026-11-05 회귀 고정:
 *
 * <ul>
 *   <li>요청 필터 값이 첫 하위 span 이 끝나면 MDC 에서 사라졌다(사가가 아닌 요청의 로그에 correlationId 가 없던 원인).
 *   <li>요청 값을 baggage 에 넣어 고치려 하자, 사가 구간({@link CorrelationScope}) 안의 하위 span 이 요청 span 의 baggage 를
 *       물려받아 sagaId 를 요청 값으로 덮었다(order-orchestration CheckoutSagaIT 실패).
 * </ul>
 *
 * 둘 다 correlationId 를 추적 라이브러리의 MDC 연동 필드에서 빼서 고쳤다(ObservabilityDefaultsPostProcessor).
 */
@SpringBootTest(
    classes = CorrelationIdTracingTest.App.class,
    properties = {
      "spring.main.banner-mode=off",
      "management.tracing.export.zipkin.enabled=false",
      "management.tracing.export.enabled=false"
    })
@AutoConfigureMockMvc
class CorrelationIdTracingTest {

  static final Map<String, @Nullable String> SEEN = new LinkedHashMap<>();

  @SpringBootApplication
  static class App {

    @RestController
    static class Api {
      private final Tracer tracer;

      Api(Tracer tracer) {
        this.tracer = tracer;
      }

      /** 요청 안의 하위 처리(DB·HTTP 호출 관측 등)가 새 span 을 여는 상황. */
      private void nested(String prefix) {
        Span inner = tracer.nextSpan().name("inner").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(inner)) {
          SEEN.put(prefix + ".inner", MDC.get(CorrelationIdFilter.MDC_KEY));
          SEEN.put(prefix + ".traceId", MDC.get("traceId"));
        } finally {
          inner.end();
        }
        SEEN.put(prefix + ".after", MDC.get(CorrelationIdFilter.MDC_KEY));
      }

      @GetMapping("/plain")
      String plain() {
        SEEN.put("outer", MDC.get(CorrelationIdFilter.MDC_KEY));
        nested("plain");
        return "ok";
      }

      /** 사가 진행처럼 요청 안에서 sagaId 로 상관 범위를 여는 상황. */
      @GetMapping("/saga")
      String saga() {
        try (CorrelationScope c =
            CorrelationScope.open(tracer, "SAGA-11111111-2222-3333-4444-555555555555")) {
          nested("saga");
        }
        SEEN.put("afterSaga", MDC.get(CorrelationIdFilter.MDC_KEY));
        return "ok";
      }
    }
  }

  @Autowired MockMvc mvc;

  @BeforeEach
  void clear() {
    SEEN.clear();
  }

  @Test
  void requestValueSurvivesNestedSpans() throws Exception {
    MvcResult r =
        mvc.perform(get("/plain").header(CorrelationIdFilter.HEADER, "corr-abc-1")).andReturn();

    assertThat(r.getResponse().getHeader(CorrelationIdFilter.HEADER)).isEqualTo("corr-abc-1");
    assertThat(SEEN.get("plain.traceId")).as("추적이 실제로 켜져 있다").isNotBlank();
    assertThat(SEEN)
        .containsEntry("outer", "corr-abc-1")
        .containsEntry("plain.inner", "corr-abc-1")
        .containsEntry("plain.after", "corr-abc-1");
  }

  @Test
  void generatedValueSurvivesNestedSpans() throws Exception {
    MvcResult r = mvc.perform(get("/plain")).andReturn();

    String generated = r.getResponse().getHeader(CorrelationIdFilter.HEADER);
    assertThat(generated).isNotBlank();
    assertThat(SEEN)
        .containsEntry("outer", generated)
        .containsEntry("plain.inner", generated)
        .containsEntry("plain.after", generated);
  }

  @Test
  void sagaScopeWinsInsideAndRequestValueComesBackAfter() throws Exception {
    mvc.perform(get("/saga").header(CorrelationIdFilter.HEADER, "corr-request-1")).andReturn();

    assertThat(SEEN)
        .containsEntry("saga.inner", "SAGA-11111111-2222-3333-4444-555555555555")
        .containsEntry("saga.after", "SAGA-11111111-2222-3333-4444-555555555555")
        .containsEntry("afterSaga", "corr-request-1");
  }

  @Test
  void nothingLeaksAfterTheRequest() throws Exception {
    mvc.perform(get("/plain").header(CorrelationIdFilter.HEADER, "corr-leak-check")).andReturn();
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }
}
