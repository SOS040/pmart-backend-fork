package com.pmart.mfc.inventory.application;

import java.util.LinkedHashMap;
import java.util.Map;
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

  public Map<String, Integer> getMinRemainingDays() {
    return minRemainingDays;
  }

  public void setMinRemainingDays(Map<String, Integer> minRemainingDays) {
    this.minRemainingDays = minRemainingDays;
  }
}
