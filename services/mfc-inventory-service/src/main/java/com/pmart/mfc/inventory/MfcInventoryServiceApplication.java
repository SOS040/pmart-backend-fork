package com.pmart.mfc.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mfc-inventory-service. 책임·비소유는 services/mfc-inventory-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class MfcInventoryServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MfcInventoryServiceApplication.class, args);
  }
}
