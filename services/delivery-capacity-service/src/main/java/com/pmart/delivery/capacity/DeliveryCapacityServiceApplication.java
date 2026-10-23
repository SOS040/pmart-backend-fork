package com.pmart.delivery.capacity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * delivery-capacity-service. 책임·비소유는 services/delivery-capacity-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class DeliveryCapacityServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DeliveryCapacityServiceApplication.class, args);
  }
}
