package com.pmart.mfc.quality.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.quality.*}.
 *
 * @param mfcInventoryBaseUrl mfc-inventory 주소(바꾸면 재시작)
 */
@ConfigurationProperties(prefix = "pmart.quality")
public record QualityProperties(@Nullable String mfcInventoryBaseUrl) {

  public String inventoryUrl() {
    return mfcInventoryBaseUrl == null || mfcInventoryBaseUrl.isBlank()
        ? "http://mfc-inventory-service:8301"
        : mfcInventoryBaseUrl;
  }
}
