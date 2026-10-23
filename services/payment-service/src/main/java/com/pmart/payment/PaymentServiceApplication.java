package com.pmart.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * payment-service. 책임·비소유는 services/payment-service/CLAUDE.md 와 docs/architecture/service-map.md.
 */
@SpringBootApplication
public class PaymentServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(PaymentServiceApplication.class, args);
  }
}
