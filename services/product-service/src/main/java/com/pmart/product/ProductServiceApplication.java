package com.pmart.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-service. 책임·비소유는 services/product-service/CLAUDE.md 와 docs/architecture/service-map.md.
 */
@SpringBootApplication
public class ProductServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ProductServiceApplication.class, args);
  }
}
