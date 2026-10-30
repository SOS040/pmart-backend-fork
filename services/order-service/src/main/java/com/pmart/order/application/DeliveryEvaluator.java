package com.pmart.order.application;

import com.pmart.common.web.PmartException;
import com.pmart.order.domain.OrderRules;
import com.pmart.order.infrastructure.Upstreams;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * 배송 방식별 지금 가능 여부(BR-13, Commerce 9장). 조회 실패는 불가가 아니라 UNKNOWN(LOOKUP_FAILED) — 배달 불가로 표시하지 않는다.
 *
 * <ul>
 *   <li>한집·알뜰: 00:00~08:00 이면 NIGHT_TIME(예약만). 그 밖에는 센터 고객 주문 운영 구간 안이어야 한다(CENTER_CLOSED).
 *   <li>예약: 오늘·내일 슬롯 중 가장 빠른 AVAILABLE. 없으면 NO_SLOT.
 *   <li>운영 시간표·정원 미설정은 CONFIG_REQUIRED.
 * </ul>
 */
@Service
public class DeliveryEvaluator {

  private static final Logger log = LoggerFactory.getLogger(DeliveryEvaluator.class);

  public record Window(LocalDate date, String start, String end) {}

  public record ModeResult(
      String mode, String availability, @Nullable String reason, @Nullable Window earliestSlot) {
    public boolean available() {
      return "AVAILABLE".equals(availability);
    }
  }

  private final Upstreams upstreams;

  public DeliveryEvaluator(Upstreams upstreams) {
    this.upstreams = upstreams;
  }

  public ModeResult evaluate(String mode, String mfcId, Instant now) {
    try {
      return OrderRules.SCHEDULED.equals(mode)
          ? scheduled(mfcId, now)
          : immediate(mode, mfcId, now);
    } catch (PmartException e) {
      if ("CONFIG_REQUIRED".equals(e.code())) {
        return new ModeResult(mode, "CONFIG_REQUIRED", "CONFIG_REQUIRED", null);
      }
      log.warn("배송 방식 {} 판정 조회 실패({}) — UNKNOWN", mode, e.code());
      return new ModeResult(mode, "UNKNOWN", "LOOKUP_FAILED", null);
    } catch (RestClientException e) {
      log.warn("배송 방식 {} 판정 조회 실패 — UNKNOWN", mode);
      return new ModeResult(mode, "UNKNOWN", "LOOKUP_FAILED", null);
    }
  }

  /** 고른 예약 시간대가 지금 선택 가능한가. 비어 있으면 그 시간대 상태, 가능하면 empty. */
  public Optional<String> slotProblem(String mfcId, Window window) {
    try {
      Optional<Upstreams.Slot> slot =
          upstreams.slots(mfcId, window.date()).stream()
              .filter(s -> s.start().equals(window.start()) && s.end().equals(window.end()))
              .findFirst();
      if (slot.isEmpty()) {
        return Optional.of("NOT_OFFERED"); // 오늘·내일 제공 시간대가 아님(08시 전·영업 밖 등)
      }
      return "AVAILABLE".equals(slot.get().availability())
          ? Optional.empty()
          : Optional.of(slot.get().availability());
    } catch (PmartException e) {
      return Optional.of("CONFIG_REQUIRED".equals(e.code()) ? "CONFIG_REQUIRED" : "LOOKUP_FAILED");
    } catch (RestClientException e) {
      return Optional.of("LOOKUP_FAILED");
    }
  }

  private ModeResult immediate(String mode, String mfcId, Instant now) {
    if (OrderRules.nightTime(now)) {
      return new ModeResult(mode, "UNAVAILABLE", "NIGHT_TIME", null);
    }
    Upstreams.CenterDay day =
        upstreams.centerDay(mfcId, LocalDate.ofInstant(now, OrderRules.SEOUL));
    if (day.configRequired()) {
      return new ModeResult(mode, "CONFIG_REQUIRED", "CONFIG_REQUIRED", null);
    }
    if (!day.open() || !OrderRules.within(now, day.ranges())) {
      return new ModeResult(mode, "UNAVAILABLE", "CENTER_CLOSED", null);
    }
    return new ModeResult(mode, "AVAILABLE", null, null);
  }

  private ModeResult scheduled(String mfcId, Instant now) {
    LocalDate today = LocalDate.ofInstant(now, OrderRules.SEOUL);
    for (LocalDate d : List.of(today, today.plusDays(1))) {
      for (Upstreams.Slot s : upstreams.slots(mfcId, d)) {
        if ("AVAILABLE".equals(s.availability())) {
          return new ModeResult(
              OrderRules.SCHEDULED, "AVAILABLE", null, new Window(d, s.start(), s.end()));
        }
      }
    }
    return new ModeResult(OrderRules.SCHEDULED, "UNAVAILABLE", "NO_SLOT", null);
  }
}
