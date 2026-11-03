package com.pmart.customer.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.customer.*}.
 *
 * @param addressLimit 주소록 최대 건수(정책값). 비우면 주소 추가는 CONFIG_REQUIRED
 * @param serviceabilityBaseUrl delivery-serviceability-service 주소(Service DNS)
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.customer")
public class CustomerProperties {

  private final @Nullable Integer addressLimit;
  private final String serviceabilityBaseUrl;

  public CustomerProperties(@Nullable Integer addressLimit, String serviceabilityBaseUrl) {
    serviceabilityBaseUrl =
        serviceabilityBaseUrl == null || serviceabilityBaseUrl.isBlank()
            ? "http://delivery-serviceability-service:8401"
            : serviceabilityBaseUrl;
    this.addressLimit = addressLimit;
    this.serviceabilityBaseUrl = serviceabilityBaseUrl;
  }

  public @Nullable Integer addressLimit() {
    return addressLimit;
  }

  public String serviceabilityBaseUrl() {
    return serviceabilityBaseUrl;
  }
}
