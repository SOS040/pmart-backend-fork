package com.pmart.riderauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * rider-auth-service. 책임·비소유는 services/rider-auth-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RiderAuthServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(RiderAuthServiceApplication.class, args);
  }
}
