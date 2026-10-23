package com.pmart.fulfillment.routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * fulfillment-routing-service. 책임·비소유는 services/fulfillment-routing-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class FulfillmentRoutingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(FulfillmentRoutingServiceApplication.class, args);
  }
}
