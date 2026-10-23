package com.pmart.mfc.capacity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mfc-capacity-service. 책임·비소유는 services/mfc-capacity-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class MfcCapacityServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MfcCapacityServiceApplication.class, args);
  }
}
