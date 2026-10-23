package com.pmart.delivery.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * delivery-dispatch-service. 책임·비소유는 services/delivery-dispatch-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class DeliveryDispatchServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DeliveryDispatchServiceApplication.class, args);
  }
}
