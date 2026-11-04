package com.pmart.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** ai-assistant-service. 책임·비소유는 services/ai-assistant-service/CLAUDE.md, 결정은 ADR-0036. */
@SpringBootApplication
@EnableScheduling
public class AiAssistantServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AiAssistantServiceApplication.class, args);
  }
}
