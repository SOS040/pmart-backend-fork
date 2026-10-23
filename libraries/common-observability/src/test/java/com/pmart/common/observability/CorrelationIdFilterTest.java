package com.pmart.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void reusesIncomingCorrelationIdAndEchoesIt() throws ServletException, IOException {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders");
    req.addHeader(CorrelationIdFilter.HEADER, "saga-123");
    req.addHeader(CorrelationIdFilter.SOURCE_SYSTEM_HEADER, "customer-app");
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicReference<String> seenInChain = new AtomicReference<>();
    AtomicReference<String> seenSource = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
            seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            seenSource.set(MDC.get(CorrelationIdFilter.SOURCE_SYSTEM_MDC_KEY));
          }
        };

    filter.doFilter(req, res, chain);

    assertThat(seenInChain.get()).isEqualTo("saga-123");
    assertThat(seenSource.get()).isEqualTo("customer-app");
    assertThat(res.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("saga-123");
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull(); // cleared after request
  }

  @Test
  void generatesWhenMissingOrUnsafe() throws ServletException, IOException {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader(CorrelationIdFilter.HEADER, "bad\nvalue");
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter.doFilter(req, res, new MockFilterChain());

    String generated = res.getHeader(CorrelationIdFilter.HEADER);
    assertThat(generated).isNotNull().hasSize(36).doesNotContain("\n");
  }

  @Test
  void sanitizeRejectsControlCharsAndLongValues() {
    assertThat(CorrelationIdFilter.sanitize("ok-1")).isEqualTo("ok-1");
    assertThat(CorrelationIdFilter.sanitize("  ")).isNull();
    assertThat(CorrelationIdFilter.sanitize("a\u0007b")).isNull();
    assertThat(CorrelationIdFilter.sanitize("x".repeat(129))).isNull();
  }
}
