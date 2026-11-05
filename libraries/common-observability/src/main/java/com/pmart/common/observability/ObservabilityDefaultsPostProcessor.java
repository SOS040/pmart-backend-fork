package com.pmart.common.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 관측 기본값을 <b>가장 낮은 우선순위</b> property source로 넣는다. 서비스의 application.yml·환경변수가 항상 이긴다.
 *
 * <ul>
 *   <li>로그: {@code logging.config=classpath:pmart-logback.xml} (JSON 구조화, local 프로파일은 평문)
 *   <li>추적: W3C 전파, correlationId 를 baggage 로 원격 전파한다(MDC 는 우리 코드가 관리 — 아래 correlation.fields 설명)
 *   <li>메트릭: Prometheus 노출, application 태그
 *   <li>Actuator: health/info/prometheus/metrics 만 노출, liveness/readiness probe
 *   <li>Kafka·HTTP 클라이언트 observation 켬 (해당 starter 가 있을 때만 효력)
 * </ul>
 */
public class ObservabilityDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String SOURCE_NAME = "pmart-observability-defaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("logging.config", "classpath:pmart-logback.xml");

    // Boot 4: management.tracing.enabled·management.zipkin.tracing.* 는 바인딩되지 않는다(export.* 로 대체)
    defaults.put("management.tracing.export.enabled", "true");
    defaults.put("management.tracing.propagation.type", "W3C");
    defaults.put("management.tracing.sampling.probability", "1.0");
    defaults.put("management.tracing.baggage.enabled", "true");
    defaults.put("management.tracing.baggage.remote-fields", "correlationId,X-Source-System");
    defaults.put("management.tracing.baggage.correlation.enabled", "true");
    // correlationId 는 넣지 않는다(2026-11-05): 넣으면 추적 라이브러리가 span 범위를 여닫을 때 MDC correlationId 를 그 span 의
    // baggage 값으로 덮거나 지운다. 그래서 ① 요청 필터가 넣은 값이 첫 하위 span 이 끝나면 로그에서 사라졌고, ② 요청 값을 baggage 에
    // 넣으면 사가 구간의 하위 span(참여자 HTTP 호출 등)이 요청 span 의 baggage 를 물려받아 sagaId 를 요청 값으로 덮었다.
    // MDC correlationId 는 우리 코드가 관리한다:
    // CorrelationIdFilter(요청)·CorrelationScope(사가·결제)·EventConsumers(소비)·
    // MdcContextPropagation(스레드). 원격 전파는 X-Correlation-Id 헤더와 baggage(remote-fields) 그대로.
    defaults.put("management.tracing.baggage.correlation.fields", "X-Source-System");
    defaults.put("management.tracing.export.zipkin.enabled", "true");

    defaults.put("management.prometheus.metrics.export.enabled", "true");
    defaults.put("management.metrics.tags.application", "${spring.application.name:unknown}");
    defaults.put(
        "management.observations.key-values.service", "${spring.application.name:unknown}");

    defaults.put("management.endpoints.web.exposure.include", "health,info,prometheus,metrics");
    defaults.put("management.endpoint.health.probes.enabled", "true");
    defaults.put("management.endpoint.health.show-details", "never");
    defaults.put("management.info.build.enabled", "true");
    defaults.put("management.info.git.mode", "simple");

    defaults.put("spring.kafka.template.observation-enabled", "true");
    defaults.put("spring.kafka.listener.observation-enabled", "true");

    environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
