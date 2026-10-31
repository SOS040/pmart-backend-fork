package com.pmart.delivery.capacity.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.capacity.*}.
 *
 * @param slotCapacity 1시간 슬롯당 정원(승인 정책값). 비우면 슬롯은 CONFIG_REQUIRED(선택 불가)
 * @param centerMasterBaseUrl center-master-service(내부 캘린더)
 */
@ConfigurationProperties(prefix = "pmart.capacity")
public record CapacityProperties(@Nullable Integer slotCapacity, String centerMasterBaseUrl) {

  public CapacityProperties {
    centerMasterBaseUrl =
        centerMasterBaseUrl == null || centerMasterBaseUrl.isBlank()
            ? "http://center-master-service:8507"
            : centerMasterBaseUrl;
    if (slotCapacity != null && slotCapacity <= 0) {
      slotCapacity = null;
    }
  }
}
