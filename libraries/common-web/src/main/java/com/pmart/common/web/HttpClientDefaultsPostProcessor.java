package com.pmart.common.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 서비스 간 HTTP 호출 기본 타임아웃(api-and-events.md "타임아웃은 반드시 명시"). <b>가장 낮은 우선순위</b>라 서비스 설정이 항상 이긴다.
 *
 * <p>Boot 자동 구성 {@code RestClient.Builder} 가 쓰는 {@code spring.http.clients.*} 에 연결 1초·응답 5초를 둔다.
 * 호출별로 더 짧거나 길어야 하면 서비스 설정이나 개별 클라이언트에서 바꾼다. 시간 초과의 해석은 {@link Downstream}.
 */
public class HttpClientDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String SOURCE_NAME = "pmart-http-client-defaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("spring.http.clients.connect-timeout", "1s");
    d.put("spring.http.clients.read-timeout", "5s");
    environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, d));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
