package com.pmart.mfc.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mfc-fulfillment-service. 책임·비소유는 services/mfc-fulfillment-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class MfcFulfillmentServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MfcFulfillmentServiceApplication.class, args);
  }
}
