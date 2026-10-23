package com.pmart.delivery.serviceability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * delivery-serviceability-service. 책임·비소유는 services/delivery-serviceability-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class DeliveryServiceabilityServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DeliveryServiceabilityServiceApplication.class, args);
  }
}
