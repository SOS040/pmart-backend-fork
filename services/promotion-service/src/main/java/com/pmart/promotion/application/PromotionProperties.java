package com.pmart.promotion.application;

import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** pmart.promotion.* — zone: 쿠폰 "받은 날부터 N일"의 날짜 경계(없으면 Asia/Seoul, ADR-0010). */
@ConfigurationProperties("pmart.promotion")
public record PromotionProperties(@Nullable ZoneId zone) {
  public ZoneId zoneOrDefault() {
    return zone == null ? ZoneId.of("Asia/Seoul") : zone;
  }
}
