package com.pmart.mailprovisioner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * mail-provisioner — 사서함 프로비저닝 사이드카(ADR-0056 결정 10). platform-services/mail-provisioner/CLAUDE.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MailProvisionerApplication {
  public static void main(String[] args) {
    SpringApplication.run(MailProvisionerApplication.class, args);
  }
}
