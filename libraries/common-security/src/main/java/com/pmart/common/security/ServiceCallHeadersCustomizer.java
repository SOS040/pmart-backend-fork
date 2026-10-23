package com.pmart.common.security;

import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.web.client.RestClient;

/**
 * 서비스 간 호출(RestClient)에 공통 헤더를 붙인다.
 *
 * <ul>
 *   <li>{@code Authorization: Bearer ...} — 현재 요청의 토큰을 그대로 전파(사용자 컨텍스트). 서비스 자체 신원 토큰(client
 *       credentials)은 auth-service 가 생기는 3단계에서 {@code ServiceTokenSupplier} 로 대체한다.
 *   <li>{@code X-Source-System} — 이 서비스 이름
 *   <li>{@code X-Correlation-Id} — MDC 의 correlationId (사가 ID). traceparent 는 Micrometer Tracing 이
 *       붙인다.
 * </ul>
 */
public class ServiceCallHeadersCustomizer implements RestClientCustomizer {

  private final String serviceName;

  public ServiceCallHeadersCustomizer(String serviceName) {
    this.serviceName = serviceName;
  }

  @Override
  public void customize(RestClient.Builder builder) {
    builder.requestInitializer(
        request -> {
          request.getHeaders().set("X-Source-System", serviceName);
          String correlation = MDC.get("correlationId");
          if (correlation != null && !request.getHeaders().containsHeader("X-Correlation-Id")) {
            request.getHeaders().set("X-Correlation-Id", correlation);
          }
          if (!request.getHeaders().containsHeader("Authorization")) {
            PmartPrincipal.currentTokenValue()
                .ifPresent(token -> request.getHeaders().setBearerAuth(token));
          }
        });
  }
}
