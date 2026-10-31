package com.pmart.common.security;

import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.web.client.RestClient;

/**
 * 서비스 간 호출(RestClient)에 공통 헤더를 붙인다.
 *
 * <ul>
 *   <li>{@code X-Source-System} — 이 서비스 이름. 내부 경로를 받는 쪽은 이 이름을 호출자(서비스 주체)로 쓴다(ADR-0026).
 *   <li>{@code X-Correlation-Id} — MDC 의 correlationId (사가 ID). traceparent 는 Micrometer Tracing 이
 *       붙인다.
 *   <li>{@code Authorization} — 이미 지정돼 있으면 그대로 둔다. 그 외에는
 *       <ul>
 *         <li>{@code /internal/} 경로: 붙이지 않는다(서비스 간 인증 없음, ADR-0026). 행위자(사용자)는 본문으로 전달한다(예: {@link
 *             ActionAuthorizer}의 actor).
 *         <li>그 외: 현재 요청의 사용자 토큰을 그대로 전파.
 *       </ul>
 * </ul>
 */
public class ServiceCallHeadersCustomizer implements RestClientCustomizer {

  private static final String INTERNAL_SEGMENT = "/internal/";

  private final String serviceName;

  public ServiceCallHeadersCustomizer(String serviceName) {
    this.serviceName = serviceName;
  }

  @Override
  public void customize(RestClient.Builder builder) {
    // requestInitializer 는 요청별 헤더가 붙기 전에 실행돼 "이미 지정된 Authorization" 을 볼 수 없다 → 실행 직전의 interceptor
    // 로.
    builder.requestInterceptor(
        (request, body, execution) -> {
          request.getHeaders().set(InternalCallerFilter.SOURCE_HEADER, serviceName);
          String correlation = MDC.get("correlationId");
          if (correlation != null && !request.getHeaders().containsHeader("X-Correlation-Id")) {
            request.getHeaders().set("X-Correlation-Id", correlation);
          }
          if (request.getHeaders().containsHeader("Authorization")) {
            return execution.execute(request, body);
          }
          String path = request.getURI().getPath();
          if (path != null && path.contains(INTERNAL_SEGMENT)) {
            return execution.execute(request, body); // 내부 호출은 토큰 없이
          }
          PmartPrincipal.currentTokenValue()
              .ifPresent(token -> request.getHeaders().setBearerAuth(token));
          return execution.execute(request, body);
        });
  }
}
