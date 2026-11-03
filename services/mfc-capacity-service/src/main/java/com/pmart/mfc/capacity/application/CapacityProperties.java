package com.pmart.mfc.capacity.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.capacity.*}.
 *
 * @param unitsPerHour (MFC, 1시간 창)당 수용 단위(정책값). 비우면 Hold·스냅샷은 CONFIG_REQUIRED(0·무제한 아님). 0 이하도
 *     미설정으로 본다. LAN 시험값 30(주문 1건 = 1 단위, 사용자 결정 2026-10-30)은 env PMART_CAPACITY_UNITSPERHOUR
 * @param expiryInterval 만료 Hold 정리 주기
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.capacity")
public class CapacityProperties {

  private final @Nullable Integer unitsPerHour;
  private final @Nullable Duration expiryInterval;

  public CapacityProperties(@Nullable Integer unitsPerHour, @Nullable Duration expiryInterval) {
    if (unitsPerHour != null && unitsPerHour <= 0) {
      unitsPerHour = null;
    }
    expiryInterval = expiryInterval == null ? Duration.ofSeconds(30) : expiryInterval;
    this.unitsPerHour = unitsPerHour;
    this.expiryInterval = expiryInterval;
  }

  public @Nullable Integer unitsPerHour() {
    return unitsPerHour;
  }

  public @Nullable Duration expiryInterval() {
    return expiryInterval;
  }
}
