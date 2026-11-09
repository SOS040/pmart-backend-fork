package com.pmart.customerassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * customer-assistant-service(고객앱 RAG). 책임·비소유는 services/customer-assistant-service/CLAUDE.md, 결정은
 * ADR-0051.
 */
@SpringBootApplication
@EnableScheduling
public class CustomerAssistantServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CustomerAssistantServiceApplication.class, args);
  }
}
