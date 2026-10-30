package com.pmart.mfc.capacity.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.capacity.*}.
 *
 * @param unitsPerHour (MFC, 1시간 창)당 수용 단위(정책값). 비우면 Hold·스냅샷은 CONFIG_REQUIRED(0·무제한 아님). 0 이하도
 *     미설정으로 본다. LAN 시험값 30(주문 1건 = 1 단위, 사용자 결정 2026-10-30)은 env PMART_CAPACITY_UNITSPERHOUR
 * @param expiryInterval 만료 Hold 정리 주기
 */
@ConfigurationProperties(prefix = "pmart.capacity")
public record CapacityProperties(
    @Nullable Integer unitsPerHour, @Nullable Duration expiryInterval) {

  public CapacityProperties {
    if (unitsPerHour != null && unitsPerHour <= 0) {
      unitsPerHour = null;
    }
    expiryInterval = expiryInterval == null ? Duration.ofSeconds(30) : expiryInterval;
  }
}
