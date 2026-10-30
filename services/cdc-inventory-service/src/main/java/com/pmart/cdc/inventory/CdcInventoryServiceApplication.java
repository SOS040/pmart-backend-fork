package com.pmart.cdc.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * cdc-inventory-service. 책임·비소유는 services/cdc-inventory-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class CdcInventoryServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CdcInventoryServiceApplication.class, args);
  }
}
