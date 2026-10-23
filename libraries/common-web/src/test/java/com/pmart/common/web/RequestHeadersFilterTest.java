package com.pmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RequestHeadersFilterTest {

  private final CommonWebProperties properties = new CommonWebProperties();
  private final RequestHeadersFilter filter =
      new RequestHeadersFilter(properties, new ProblemDetailWriter(new ObjectMapper()));

  @Test
  void storesHeadersAsRequestAttribute() throws ServletException, IOException {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
    req.addHeader(RequestHeaders.IDEMPOTENCY_KEY, " req-1 ");
    req.addHeader(RequestHeaders.SOURCE_SYSTEM, "customer-app");
    req.addHeader(RequestHeaders.EXPECTED_VERSION, "7");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    RequestHeaders h = (RequestHeaders) req.getAttribute(RequestHeaders.ATTRIBUTE);
    assertThat(h.idempotencyKey()).isEqualTo("req-1");
    assertThat(h.sourceSystem()).isEqualTo("customer-app");
    assertThat(h.expectedVersion()).isEqualTo(7);
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void rejectsMutatingRequestWithoutIdempotencyKeyAsProblemJson()
      throws ServletException, IOException {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
    req.addHeader(RequestHeaders.SOURCE_SYSTEM, "customer-app");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(400);
    assertThat(res.getContentType()).startsWith("application/problem+json");
    assertThat(res.getContentAsString()).contains("\"code\":\"VALIDATION_FAILED\"");
    assertThat(res.getContentAsString()).contains("Idempotency-Key");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void getRequestsAndUnguardedPathsAreNotChecked() throws ServletException, IOException {
    MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/orders/1");
    MockHttpServletResponse res1 = new MockHttpServletResponse();
    filter.doFilter(get, res1, new MockFilterChain());
    assertThat(res1.getStatus()).isEqualTo(200);

    MockHttpServletRequest actuator = new MockHttpServletRequest("POST", "/actuator/refresh");
    MockHttpServletResponse res2 = new MockHttpServletResponse();
    filter.doFilter(actuator, res2, new MockFilterChain());
    assertThat(res2.getStatus()).isEqualTo(200);
  }

  @Test
  void excludedPathsSkipTheCheckButOthersStillGuarded() throws ServletException, IOException {
    properties.setExcludedPaths(java.util.List.of("/api/v1/internal/service-tokens"));

    MockHttpServletResponse excluded = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("POST", "/api/v1/internal/service-tokens"),
        excluded,
        new MockFilterChain());
    assertThat(excluded.getStatus()).isEqualTo(200);

    MockHttpServletResponse other = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("POST", "/api/v1/internal/authorizations"),
        other,
        new MockFilterChain());
    assertThat(other.getStatus()).isEqualTo(400);
  }

  @Test
  void invalidExpectedVersionIsIgnoredNotRejected() throws ServletException, IOException {
    MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/internal/x");
    req.addHeader(RequestHeaders.IDEMPOTENCY_KEY, "k");
    req.addHeader(RequestHeaders.SOURCE_SYSTEM, "s");
    req.addHeader(RequestHeaders.EXPECTED_VERSION, "abc");
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, new MockFilterChain());
    RequestHeaders h = (RequestHeaders) req.getAttribute(RequestHeaders.ATTRIBUTE);
    assertThat(h.expectedVersion()).isNull();
    assertThat(res.getStatus()).isEqualTo(200);
  }
}
