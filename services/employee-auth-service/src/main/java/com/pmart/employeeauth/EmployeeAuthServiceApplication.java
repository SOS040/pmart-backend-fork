package com.pmart.employeeauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * employee-auth-service. 책임·비소유는 services/employee-auth-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EmployeeAuthServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(EmployeeAuthServiceApplication.class, args);
  }
}
