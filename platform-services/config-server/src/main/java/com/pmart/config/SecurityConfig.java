package com.pmart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 설정 조회는 Basic 인증(spring.security.user). 상태 확인만 토큰 없이 연다.
 *
 * <p>{@code /encrypt}·{@code /decrypt} 는 막는다. 인증 정보 하나로 저장소의 모든 비밀을 복호화할 수 있게 되기 때문이다. 암호화는 {@code
 * configCipher} 태스크(같은 {@code EncryptorFactory})로 오프라인에서 한다.
 *
 * <p>{@code POST /monitor}(GitHub push 웹훅, ADR-0033)는 Basic 인증 대신 웹훅 서명으로 지킨다: config-monitor 의
 * {@code WebhookValidatorFilter} 가 이 체인보다 먼저 돌아 {@code X-Hub-Signature-256}(공유 비밀 HMAC)이 맞지 않으면 403
 * 으로 끝낸다. 요청이 할 수 있는 일은 "이 앱들은 설정을 다시 받아 가라"는 알림뿐이고, 값은 언제나 저장소에서 온다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain configServerSecurity(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/monitor")
                    .permitAll() // 서명 검증은 WebhookValidatorFilter(위 설명)
                    // 필터의 sendError(403)가 /error 로 넘어간다 — 막으면 거부 응답이 401 로 바뀐다
                    .requestMatchers("/error")
                    .permitAll()
                    .requestMatchers("/encrypt/**", "/decrypt/**", "/encrypt", "/decrypt")
                    .denyAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        .csrf(csrf -> csrf.disable()) // 브라우저 세션이 없다(Basic 인증·웹훅 서명)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
