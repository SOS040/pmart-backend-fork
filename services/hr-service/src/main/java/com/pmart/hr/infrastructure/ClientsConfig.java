package com.pmart.hr.infrastructure;

import com.pmart.hr.application.HrProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 외부 호출 클라이언트. 설정이 없으면 빈을 만들지 않는다 — 계정 발급은 CONFIG_REQUIRED 로 남고(재시도), 등록·조회는 된다(로컬 프로필). */
@Configuration(proxyBeanMethods = false)
class ClientsConfig {

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnExpression(
      "'${pmart.hr.keycloak.admin-base-url:}' != '' and '${pmart.hr.keycloak.client-secret:}' != ''")
  KeycloakAdminClient keycloakAdminClient(HrProperties props, Clock clock) {
    return new KeycloakAdminClient(props.keycloak(), clock);
  }

  @Bean
  @ConditionalOnExpression("'${pmart.hr.mail-provisioner.base-url:}' != ''")
  MailProvisionerClient mailProvisionerClient(HrProperties props, Environment env) {
    return new MailProvisionerClient(
        props.mailProvisioner(), env.getProperty("spring.application.name", "hr-service"));
  }
}
