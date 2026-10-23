package com.pmart.mfc.quality;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mfc-quality-service. 책임·비소유는 services/mfc-quality-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class MfcQualityServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MfcQualityServiceApplication.class, args);
  }
}
