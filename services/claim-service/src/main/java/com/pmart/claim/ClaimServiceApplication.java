package com.pmart.claim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** claim-service. 책임·비소유는 services/claim-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class ClaimServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ClaimServiceApplication.class, args);
  }
}
