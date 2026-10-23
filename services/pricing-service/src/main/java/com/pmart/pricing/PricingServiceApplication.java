package com.pmart.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * pricing-service. 책임·비소유는 services/pricing-service/CLAUDE.md 와 docs/architecture/service-map.md.
 */
@SpringBootApplication
public class PricingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(PricingServiceApplication.class, args);
  }
}
