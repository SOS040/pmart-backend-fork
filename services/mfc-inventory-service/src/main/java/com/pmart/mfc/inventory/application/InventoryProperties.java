package com.pmart.mfc.inventory.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.inventory.*}.
 *
 * <p>{@code min-remaining-days}: 보관 유형(AMBIENT/CHILLED/FROZEN) → 최소 잔여일(배송 약속일 기준, MFC 문서 §6 Q-05
 * 결정 2026-11-07: 냉장 2·냉동 30·상온 30). 소비기한이 있는 LOT 을 배정할 때만 쓴다. 그 보관 유형 값이 없으면 CONFIG_REQUIRED(0 으로
 * 대체하지 않음).
 */
@ConfigurationProperties("pmart.inventory")
public class InventoryProperties {

  private Map<String, Integer> minRemainingDays = new LinkedHashMap<>();

  /**
   * 요청에 ttlMinutes 가 없을 때의 예약 유효시간(MFC 문서 §6 Q-04 결정 2026-11-07: 10분, ADR-0042). 없으면 그런 요청은
   * CONFIG_REQUIRED.
   */
  private @Nullable Duration defaultHoldTtl;

  /**
   * 실제 상품 크기 정보가 없을 때 재고 이동 용량을 더미 크기(소·중·대, SKU 로 고정 배정)로 계산할지(2026-11-08 사용자 요청: 시스템 구현용 더미).
   * LAN·시험만 켠다. 꺼져 있으면 이동 배정은 CONFIG_REQUIRED.
   */
  private boolean dummySkuSize;

  public boolean isDummySkuSize() {
    return dummySkuSize;
  }

  public void setDummySkuSize(boolean dummySkuSize) {
    this.dummySkuSize = dummySkuSize;
  }

  public @Nullable Duration getDefaultHoldTtl() {
    return defaultHoldTtl;
  }

  public void setDefaultHoldTtl(@Nullable Duration defaultHoldTtl) {
    this.defaultHoldTtl = defaultHoldTtl;
  }

  public Map<String, Integer> getMinRemainingDays() {
    return minRemainingDays;
  }

  public void setMinRemainingDays(Map<String, Integer> minRemainingDays) {
    this.minRemainingDays = minRemainingDays;
  }
}
