package com.pmart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 설정 조회는 Basic 인증(spring.security.user). 상태 확인만 토큰 없이 연다.
 *
 * <p>{@code /encrypt}·{@code /decrypt} 는 막는다. 인증 정보 하나로 저장소의 모든 비밀을 복호화할 수 있게 되기 때문이다. 암호화는 {@code
 * configCipher} 태스크(같은 {@code EncryptorFactory})로 오프라인에서 한다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain configServerSecurity(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/encrypt/**", "/decrypt/**", "/encrypt", "/decrypt")
                    .denyAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        .csrf(csrf -> csrf.disable()) // 상태를 바꾸는 엔드포인트가 없다(조회만)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
