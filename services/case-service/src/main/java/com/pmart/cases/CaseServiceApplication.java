package com.pmart.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** case-service. 책임·비소유는 services/case-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class CaseServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CaseServiceApplication.class, args);
  }
}
