package com.pmart.mailprovisioner.infrastructure;

import com.pmart.mailprovisioner.application.MailProvisionerProperties;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 계정 파일·SMTP 확인. */
@Configuration(proxyBeanMethods = false)
class MailConfig {

  @Bean
  AccountsFile accountsFile(MailProvisionerProperties props) {
    return new AccountsFile(Objects.requireNonNull(props.accountsFile()));
  }

  @Bean
  SmtpProbe smtpProbe(MailProvisionerProperties props) {
    return new SmtpProbe(
        Objects.requireNonNull(props.smtpHost()),
        props.smtpPort(),
        Objects.requireNonNull(props.heloName()),
        Objects.requireNonNull(props.smtpTimeout()));
  }
}
