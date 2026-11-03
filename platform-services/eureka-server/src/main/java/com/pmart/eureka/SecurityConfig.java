package com.pmart.eureka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 등록·조회(/eureka/**)와 대시보드는 Basic 인증(spring.security.user). 상태 확인과 지표만 토큰 없이
 * 연다(watch.sh·Prometheus).
 *
 * <p>인증 없이 열어 두면 LAN 의 누구나 가짜 인스턴스를 등록해 서비스 간 호출을 가로챌 수 있다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain eurekaServerSecurity(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        // Eureka 클라이언트는 POST·PUT·DELETE 로 등록·갱신·해제한다(브라우저 세션이 아니라 Basic 헤더)
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
