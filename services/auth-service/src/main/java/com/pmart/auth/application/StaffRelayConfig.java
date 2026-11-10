package com.pmart.auth.application;

import com.pmart.auth.infrastructure.KeycloakAdminClient;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keycloak 직원 차단 중계(ADR-0054 ⑤ 결정 M) — {@code pmart.auth.staff-relay.enabled=true} 일 때만(LAN). 값은
 * pmart-config {@code auth-service-<프로필>.yml}, 비밀값은 환경변수.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StaffRelayConfig.Settings.class)
@ConditionalOnProperty(prefix = "pmart.auth.staff-relay", name = "enabled", havingValue = "true")
@EnableScheduling
public class StaffRelayConfig {

  /**
   * {@code pmart.auth.staff-relay.*}.
   *
   * @param baseUrl Keycloak 주소(예 http://192.168.0.101:8180)
   * @param realm 직원 Realm(pmart-staff)
   * @param clientId 중계 클라이언트(pmart-auth-relay — 서비스 계정 view-events·view-users 만)
   * @param clientSecret 그 비밀값(환경변수, 로그 금지)
   * @param issuer 직원 토큰의 {@code iss}(발급자 표 staff 줄과 같다) — 사건 {@code issuer}
   * @param operatorIdAttribute 운영자 ID 사용자 속성(발급자 표 staff 줄 subject-claim 과 같은 이름)
   * @param lookback 매번 되돌아 읽는 기간(기본 1일). 이보다 오래 멈춰 있었으면 그 사이 차단은 놓친다 — 직원 토큰 15분이라 그때는 이미 만료
   * @param timeout Keycloak 호출 상한
   */
  @ConfigurationProperties(prefix = "pmart.auth.staff-relay")
  public record Settings(
      boolean enabled,
      String baseUrl,
      @Nullable String realm,
      @Nullable String clientId,
      String clientSecret,
      String issuer,
      @Nullable String operatorIdAttribute,
      @Nullable Duration lookback,
      @Nullable Duration timeout) {
    public Settings {
      realm = realm == null || realm.isBlank() ? "pmart-staff" : realm;
      clientId = clientId == null || clientId.isBlank() ? "pmart-auth-relay" : clientId;
      operatorIdAttribute =
          operatorIdAttribute == null || operatorIdAttribute.isBlank()
              ? "pmart_operator_id"
              : operatorIdAttribute;
      lookback = lookback == null ? Duration.ofDays(1) : lookback;
      timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
      if (enabled && (isBlank(baseUrl) || isBlank(clientSecret) || isBlank(issuer))) {
        throw new IllegalArgumentException(
            "pmart.auth.staff-relay 를 켜려면 base-url·client-secret·issuer 가 필요합니다");
      }
    }

    private static boolean isBlank(@Nullable String s) {
      return s == null || s.isBlank();
    }
  }

  @Bean
  StaffBlockRelay staffBlockRelay(
      Settings s, SubjectBlocks blocks, JdbcTemplate jdbc, TransactionTemplate tx) {
    Clock clock = Clock.systemUTC();
    KeycloakAdminClient keycloak =
        new KeycloakAdminClient(
            s.baseUrl(),
            s.realm(),
            s.clientId(),
            s.clientSecret(),
            s.operatorIdAttribute(),
            s.timeout(),
            clock);
    return new StaffBlockRelay(keycloak, blocks, jdbc, tx, s.issuer(), s.lookback(), clock);
  }
}
