package com.pmart.center.master;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * center-master-service. 책임·비소유는 services/center-master-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class CenterMasterServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CenterMasterServiceApplication.class, args);
  }
}
