package com.pmart.promotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * promotion-service — 쿠폰·기간 할인(ADR-0082). 책임은 services/promotion-service/CLAUDE.md 와 service-map.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PromotionServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(PromotionServiceApplication.class, args);
  }
}
