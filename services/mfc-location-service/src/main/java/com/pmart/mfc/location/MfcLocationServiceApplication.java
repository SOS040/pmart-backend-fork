package com.pmart.mfc.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mfc-location-service. 책임·비소유는 services/mfc-location-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class MfcLocationServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MfcLocationServiceApplication.class, args);
  }
}
