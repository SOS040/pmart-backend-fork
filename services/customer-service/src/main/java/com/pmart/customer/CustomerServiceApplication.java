package com.pmart.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * customer-service. 책임·비소유는 services/customer-service/CLAUDE.md 와 docs/architecture/service-map.md.
 */
@SpringBootApplication
public class CustomerServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CustomerServiceApplication.class, args);
  }
}
