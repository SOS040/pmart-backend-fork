package com.pmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class HttpClientDefaultsPostProcessorTest {

  @Test
  void setsTimeoutsAsLowestPrecedenceDefaults() {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(
            new MapPropertySource("service", Map.of("spring.http.clients.read-timeout", "2s")));

    new HttpClientDefaultsPostProcessor().postProcessEnvironment(env, null);

    assertThat(env.getProperty("spring.http.clients.connect-timeout")).isEqualTo("1s");
    assertThat(env.getProperty("spring.http.clients.read-timeout")).isEqualTo("2s");
  }
}
