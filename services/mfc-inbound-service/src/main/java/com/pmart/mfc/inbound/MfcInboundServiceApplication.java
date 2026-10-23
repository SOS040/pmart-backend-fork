package com.pmart.mfc.inbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mfc-inbound-service. 책임·비소유는 services/mfc-inbound-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class MfcInboundServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MfcInboundServiceApplication.class, args);
  }
}
