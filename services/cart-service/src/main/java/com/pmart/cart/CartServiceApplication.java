package com.pmart.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** cart-service. 책임·비소유는 services/cart-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class CartServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CartServiceApplication.class, args);
  }
}
