package com.pmart.delivery.capacity.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.capacity.*}.
 *
 * @param slotCapacity 1시간 슬롯당 정원(승인 정책값). 비우면 슬롯은 CONFIG_REQUIRED(선택 불가)
 * @param centerMasterBaseUrl center-master-service(내부 캘린더)
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.capacity")
public class CapacityProperties {

  private final @Nullable Integer slotCapacity;
  private final String centerMasterBaseUrl;

  public CapacityProperties(@Nullable Integer slotCapacity, String centerMasterBaseUrl) {
    centerMasterBaseUrl =
        centerMasterBaseUrl == null || centerMasterBaseUrl.isBlank()
            ? "http://center-master-service:8507"
            : centerMasterBaseUrl;
    if (slotCapacity != null && slotCapacity <= 0) {
      slotCapacity = null;
    }
    this.slotCapacity = slotCapacity;
    this.centerMasterBaseUrl = centerMasterBaseUrl;
  }

  public @Nullable Integer slotCapacity() {
    return slotCapacity;
  }

  public String centerMasterBaseUrl() {
    return centerMasterBaseUrl;
  }
}
