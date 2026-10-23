package com.pmart.product.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-content-service. 책임·비소유는 services/product-content-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class ProductContentServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ProductContentServiceApplication.class, args);
  }
}
