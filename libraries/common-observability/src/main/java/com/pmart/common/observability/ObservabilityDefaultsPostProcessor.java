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
 *   <li>추적: W3C 전파, correlationId 를 baggage 로 원격 전파하고 MDC 에 넣는다
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
    defaults.put("management.tracing.baggage.correlation.fields", "correlationId,X-Source-System");
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
