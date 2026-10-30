package com.pmart.order.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pmart.order.*} — 정본 서비스 주소(Service DNS). */
@ConfigurationProperties(prefix = "pmart.order")
public record OrderProperties(
    String customerBaseUrl,
    String productBaseUrl,
    String pricingBaseUrl,
    String centerMasterBaseUrl,
    String capacityBaseUrl,
    String orchestrationBaseUrl) {

  public OrderProperties {
    customerBaseUrl = or(customerBaseUrl, "http://customer-service:8103");
    productBaseUrl = or(productBaseUrl, "http://product-service:8101");
    pricingBaseUrl = or(pricingBaseUrl, "http://pricing-service:8107");
    centerMasterBaseUrl = or(centerMasterBaseUrl, "http://center-master-service:8507");
    capacityBaseUrl = or(capacityBaseUrl, "http://delivery-capacity-service:8402");
    orchestrationBaseUrl = or(orchestrationBaseUrl, "http://order-orchestration-service:8202");
  }

  private static String or(String v, String d) {
    return v == null || v.isBlank() ? d : v;
  }
}
