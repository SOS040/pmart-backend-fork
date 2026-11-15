package com.pmart.review;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** review-service. 책임·비소유는 services/review-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ReviewServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ReviewServiceApplication.class, args);
  }
}
