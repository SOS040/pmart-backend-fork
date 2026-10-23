package com.pmart.storefront;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * storefront-service. 책임·비소유는 services/storefront-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class StorefrontServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(StorefrontServiceApplication.class, args);
  }
}
