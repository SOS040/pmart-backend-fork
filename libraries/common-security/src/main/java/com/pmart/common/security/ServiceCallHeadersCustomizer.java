package com.pmart.common.security;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.web.client.RestClient;

/**
 * 서비스 간 호출(RestClient)에 공통 헤더를 붙인다.
 *
 * <ul>
 *   <li>{@code Authorization: Bearer ...} — 이미 지정돼 있으면 그대로 둔다. 그 외에는
 *       <ul>
 *         <li>{@code /internal/} 경로: 이 서비스의 신원 토큰({@link ServiceTokenProvider}, ADR-0011).
 *             행위자(사용자)는 본문으로 전달한다(예: {@link ActionAuthorizer}의 actor).
 *         <li>그 외: 현재 요청의 사용자 토큰을 그대로 전파.
 *       </ul>
 *   <li>{@code X-Source-System} — 이 서비스 이름
 *   <li>{@code X-Correlation-Id} — MDC 의 correlationId (사가 ID). traceparent 는 Micrometer Tracing 이
 *       붙인다.
 * </ul>
 *
 * <p>서비스 토큰 설정이 없는 서비스의 내부 호출은 사용자 토큰을 그대로 보낸다 — 대상이 TYPE_SERVICE 를 요구하므로 403 으로 드러난다(조용히 허용되지
 * 않는다).
 */
public class ServiceCallHeadersCustomizer implements RestClientCustomizer {

  private static final String INTERNAL_SEGMENT = "/internal/";

  private final String serviceName;
  private final @Nullable ObjectProvider<ServiceTokenProvider> serviceTokens;

  public ServiceCallHeadersCustomizer(String serviceName) {
    this(serviceName, null);
  }

  /**
   * @param serviceTokens 지연 조회 — 토큰 발급용 RestClient 도 이 customizer 를 거치므로 생성 시점 순환을 피한다.
   */
  public ServiceCallHeadersCustomizer(
      String serviceName, @Nullable ObjectProvider<ServiceTokenProvider> serviceTokens) {
    this.serviceName = serviceName;
    this.serviceTokens = serviceTokens;
  }

  @Override
  public void customize(RestClient.Builder builder) {
    // requestInitializer 는 요청별 헤더가 붙기 전에 실행돼 "이미 지정된 Authorization" 을 볼 수 없다 → 실행 직전의 interceptor
    // 로.
    builder.requestInterceptor(
        (request, body, execution) -> {
          request.getHeaders().set("X-Source-System", serviceName);
          String correlation = MDC.get("correlationId");
          if (correlation != null && !request.getHeaders().containsHeader("X-Correlation-Id")) {
            request.getHeaders().set("X-Correlation-Id", correlation);
          }
          if (request.getHeaders().containsHeader("Authorization")) {
            return execution.execute(request, body);
          }
          ServiceTokenProvider tokens =
              serviceTokens == null ? null : serviceTokens.getIfAvailable();
          String path = request.getURI().getPath();
          if (tokens != null && path != null && path.contains(INTERNAL_SEGMENT)) {
            request.getHeaders().setBearerAuth(tokens.token());
            return execution.execute(request, body);
          }
          PmartPrincipal.currentTokenValue()
              .ifPresent(token -> request.getHeaders().setBearerAuth(token));
          return execution.execute(request, body);
        });
  }
}
