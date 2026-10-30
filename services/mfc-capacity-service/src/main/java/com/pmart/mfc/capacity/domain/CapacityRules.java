package com.pmart.mfc.capacity.domain;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * 작업 여력 규칙(CapacityHold·CapacitySnapshot).
 *
 * <ul>
 *   <li>점유 단위는 (MFC, 1시간 창). 창은 Asia/Seoul 정시에 시작하는 정확히 1시간 — 그 밖의 구간은 VALIDATION_FAILED.
 *   <li>[시험 정책 2026-10-30, 사용자 결정] 주문 1건 = 수용 단위 1. 피킹·패킹·보관·인력 단계별 환산이 정해지면 {@link
 *       #unitsPerOrder()} 를 수요(demand) 기반 환산으로 바꾼다. 지금은 demand 를 검증만 한다.
 *   <li>1시간 창당 수용 단위는 정책값. 없으면 CONFIG_REQUIRED(0·무제한 아님).
 * </ul>
 */
public final class CapacityRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 정책 키(CONFIG_REQUIRED 메시지). */
  public static final String UNITS_PER_HOUR_KEY = "pmart.capacity.units-per-hour";

  /** Asia/Seoul 정시 시작 1시간 창. 저장·전송은 UTC. */
  public record HourWindow(Instant start, Instant end) {

    /** 정규형 "start/end"(UTC ISO-8601). */
    public String text() {
      return start + "/" + end;
    }
  }

  private CapacityRules() {}

  /** "start/end" ISO-8601 구간 → 1시간 창. 오프셋(Z, +09:00) 모두 받는다. */
  public static HourWindow window(@Nullable String interval) {
    if (interval == null || interval.isBlank()) {
      throw invalid("window 가 필요합니다.");
    }
    String[] parts = interval.trim().split("/", -1);
    if (parts.length != 2) {
      throw invalid("window 는 ISO-8601 구간 'start/end' 여야 합니다.");
    }
    Instant start;
    Instant end;
    try {
      start = OffsetDateTime.parse(parts[0]).toInstant();
      end = OffsetDateTime.parse(parts[1]).toInstant();
    } catch (DateTimeParseException e) {
      throw invalid("window 시각은 오프셋을 포함한 ISO-8601 이어야 합니다(예 2026-10-31T01:00:00Z).");
    }
    if (!Duration.between(start, end).equals(Duration.ofHours(1))) {
      throw invalid("window 는 정확히 1시간이어야 합니다.");
    }
    ZonedDateTime local = start.atZone(SEOUL);
    if (local.getMinute() != 0 || local.getSecond() != 0 || local.getNano() != 0) {
      throw invalid("window 는 Asia/Seoul 정시에 시작해야 합니다.");
    }
    return new HourWindow(start, end);
  }

  /** [시험 정책] 주문 1건이 소비하는 수용 단위. */
  public static int unitsPerOrder() {
    return 1;
  }

  /** 정책값 확인. null(미설정)이면 CONFIG_REQUIRED. */
  public static int requireCapacity(@Nullable Integer unitsPerHour) {
    if (unitsPerHour == null) {
      throw PmartException.configRequired(UNITS_PER_HOUR_KEY);
    }
    return unitsPerHour;
  }

  /** 남은 수용 단위(음수 없음 — 정책값을 줄여도 기존 점유는 지우지 않는다). */
  public static int available(int capacity, int held, int confirmed) {
    return Math.max(0, capacity - held - confirmed);
  }

  /** 새 Hold 가 들어갈 수 있는지. */
  public static boolean fits(int capacity, int held, int confirmed, int units) {
    return held + confirmed + units <= capacity;
  }

  private static PmartException invalid(String message) {
    return new PmartException(ErrorCode.VALIDATION_FAILED, message);
  }
}
