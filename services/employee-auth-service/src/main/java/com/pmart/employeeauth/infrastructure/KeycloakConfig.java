package com.pmart.employeeauth.infrastructure;

import com.pmart.employeeauth.application.EmployeeAuthProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Keycloak 서버 쪽 호출 클라이언트. 앱 공용 RestClient.Builder(사용자 토큰 전파·서비스 헤더)를 쓰지 않는다 — Keycloak 에는 이 서비스의
 * 클라이언트 자격만 보낸다.
 */
@Configuration(proxyBeanMethods = false)
class KeycloakConfig {

  @Bean
  KeycloakClient keycloakClient(EmployeeAuthProperties props) {
    return new KeycloakClient(props, RestClient.builder());
  }
}
