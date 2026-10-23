package com.pmart.delivery.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * delivery-execution-service. 책임·비소유는 services/delivery-execution-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class DeliveryExecutionServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DeliveryExecutionServiceApplication.class, args);
  }
}
