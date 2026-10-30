package com.pmart.customer.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.customer.*}.
 *
 * @param addressLimit 주소록 최대 건수(정책값). 비우면 주소 추가는 CONFIG_REQUIRED
 * @param serviceabilityBaseUrl delivery-serviceability-service 주소(Service DNS)
 */
@ConfigurationProperties(prefix = "pmart.customer")
public record CustomerProperties(@Nullable Integer addressLimit, String serviceabilityBaseUrl) {

  public CustomerProperties {
    serviceabilityBaseUrl =
        serviceabilityBaseUrl == null || serviceabilityBaseUrl.isBlank()
            ? "http://delivery-serviceability-service:8401"
            : serviceabilityBaseUrl;
  }
}
