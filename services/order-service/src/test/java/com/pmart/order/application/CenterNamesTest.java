package com.pmart.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pmart.order.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** BR-62(2026-11-19): 센터 이름 10분 사본, 실패는 이전 사본(없으면 null)·1분 뒤 다시. */
class CenterNamesTest {

  @Test
  void cachesNameAndKeepsPreviousOnFailure() {
    Upstreams up = mock(Upstreams.class);
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-19T00:00:00Z"));
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    when(up.centerName("MFC-1")).thenReturn("[시험] 부산진구 MFC");
    CenterNames names = new CenterNames(up, clock);
    assertThat(names.name("MFC-1")).isEqualTo("[시험] 부산진구 MFC");
    assertThat(names.name("MFC-1")).isEqualTo("[시험] 부산진구 MFC");
    verify(up, times(1)).centerName("MFC-1");

    now.set(now.get().plus(Duration.ofMinutes(11)));
    when(up.centerName("MFC-1")).thenThrow(new RuntimeException("down"));
    assertThat(names.name("MFC-1")).as("실패면 이전 사본").isEqualTo("[시험] 부산진구 MFC");
    assertThat(names.name(null)).isNull();
    when(up.centerName("MFC-X")).thenThrow(new RuntimeException("down"));
    assertThat(names.name("MFC-X")).as("사본도 없으면 null").isNull();
  }
}
