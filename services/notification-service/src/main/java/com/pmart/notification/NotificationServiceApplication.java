package com.pmart.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * notification-service. 책임·비소유는 services/notification-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
@EnableScheduling // 리뷰 요청 정리(2026-11-18 — ReviewRequestSweeper)
public class NotificationServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(NotificationServiceApplication.class, args);
  }
}
