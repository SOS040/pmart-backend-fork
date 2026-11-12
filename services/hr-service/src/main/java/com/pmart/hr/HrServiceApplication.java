package com.pmart.hr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** hr-service. 책임·비소유는 services/hr-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HrServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(HrServiceApplication.class, args);
  }
}
