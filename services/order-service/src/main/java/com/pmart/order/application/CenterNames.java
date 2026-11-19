package com.pmart.order.application;

import com.pmart.order.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 주문한 센터 이름(2026-11-19, 계약 0.15.0 — 고객 앱 BR-62, 사용자 "고객이 센터 이름 보여도 돼"). center-master 내부 센터 조회의
 * name 을 10분 사본으로 둔다. 조회 실패·모르는 센터는 null(이름을 지어내지 않는다) — 실패는 1분 뒤 다시.
 */
@Service
public class CenterNames {

  static final Duration TTL = Duration.ofMinutes(10);
  static final Duration RETRY = Duration.ofMinutes(1);

  private record Entry(@Nullable String name, Instant until) {}

  private final Upstreams upstreams;
  private final Clock clock;
  private final Map<String, Entry> cache = new ConcurrentHashMap<>();

  public CenterNames(Upstreams upstreams, Clock clock) {
    this.upstreams = upstreams;
    this.clock = clock;
  }

  public @Nullable String name(@Nullable String mfcId) {
    if (mfcId == null || mfcId.isBlank()) {
      return null;
    }
    Instant now = clock.instant();
    Entry e = cache.get(mfcId);
    if (e != null && e.until().isAfter(now)) {
      return e.name();
    }
    String name;
    Duration keep;
    try {
      name = upstreams.centerName(mfcId);
      keep = TTL;
    } catch (RuntimeException ex) {
      name = e == null ? null : e.name(); // 실패 — 이전 사본(없으면 null)
      keep = RETRY;
    }
    cache.put(mfcId, new Entry(name, now.plus(keep)));
    return name;
  }
}
