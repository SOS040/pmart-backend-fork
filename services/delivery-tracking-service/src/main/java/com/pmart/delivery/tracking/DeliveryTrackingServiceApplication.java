package com.pmart.delivery.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * delivery-tracking-service. 책임·비소유는 services/delivery-tracking-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class DeliveryTrackingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DeliveryTrackingServiceApplication.class, args);
  }
}
