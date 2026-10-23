package com.pmart.order.orchestration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * order-orchestration-service. 책임·비소유는 services/order-orchestration-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class OrderOrchestrationServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(OrderOrchestrationServiceApplication.class, args);
  }
}
