package com.pmart.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** audit-service. 책임·비소유는 services/audit-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class AuditServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AuditServiceApplication.class, args);
  }
}
