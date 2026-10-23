package com.pmart.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * approval-service. 책임·비소유는 services/approval-service/CLAUDE.md 와 docs/architecture/service-map.md.
 */
@SpringBootApplication
public class ApprovalServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ApprovalServiceApplication.class, args);
  }
}
