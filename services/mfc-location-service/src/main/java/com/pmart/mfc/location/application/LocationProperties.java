package com.pmart.mfc.location.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.location.*}.
 *
 * @param mfcInventoryBaseUrl mfc-inventory 주소(위치 폐지 전 잔량 확인, 바꾸면 재시작)
 */
@ConfigurationProperties(prefix = "pmart.location")
public record LocationProperties(@Nullable String mfcInventoryBaseUrl) {

  public String inventoryUrl() {
    return mfcInventoryBaseUrl == null || mfcInventoryBaseUrl.isBlank()
        ? "http://mfc-inventory-service:8301"
        : mfcInventoryBaseUrl;
  }
}
