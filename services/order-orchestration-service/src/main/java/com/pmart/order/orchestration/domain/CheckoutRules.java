package com.pmart.order.orchestration.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * S1 결제 진입 사가 규칙(docs/business/flows/order-checkout-saga.md §1). 이번 단계는 a~d(가격 동결 확인·재고·작업 여력·슬롯)와
 * f(결제 시작)까지. 승인 대기(g)부터는 다음 단계.
 */
public final class CheckoutRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 자원 점유 TTL(Q-04 잠정 10분, D3). */
  public static final int HOLD_TTL_MINUTES = 10;

  /** 실행 순서. HOLD_SLOT 은 예약배달만(그 밖은 SKIPPED). */
  public static final List<String> STEPS =
      List.of(
          "VALIDATE_QUOTE",
          "RESERVE_INVENTORY",
          "HOLD_CAPACITY",
          "HOLD_SLOT",
          "START_PAYMENT",
          "AWAIT_PAYMENT_RESULT");

  /** 사가가 아직 끝나지 않은 상태(같은 초안에 하나만). */
  public static final List<String> OPEN_STATES =
      List.of("RUNNING", "WAITING_RESULT", "UNKNOWN_TRACKING", "COMPENSATING");

  private CheckoutRules() {}

  /** 단계 → 자원 종류(보상 로그). 자원을 만들지 않는 단계는 null. */
  public static @Nullable String resourceType(String step) {
    return switch (step) {
      case "RESERVE_INVENTORY" -> "INVENTORY_RESERVATION";
      case "HOLD_CAPACITY" -> "CAPACITY_HOLD";
      case "HOLD_SLOT" -> "SLOT_HOLD";
      default -> null;
    };
  }

  /** 하위 명령 Idempotency-Key. 사가·단계마다 고정이라 재개해도 정본에 한 번만 반영된다. */
  public static String requestId(String sagaId, String step) {
    return sagaId + ":" + step;
  }

  public static String releaseRequestId(String sagaId, String resourceType) {
    return sagaId + ":RELEASE:" + resourceType;
  }

  /** 1시간 작업 창(Asia/Seoul 정시). 예약배달은 도착 슬롯, 즉시 배송(한집·알뜰)은 지금이 속한 시간. */
  public record Window(Instant start, Instant end) {
    public String iso() {
      return start + "/" + end;
    }
  }

  public static Window capacityWindow(
      String deliveryMode, @Nullable LocalDate date, @Nullable String start, Instant now) {
    if ("SCHEDULED".equals(deliveryMode)) {
      ZonedDateTime s =
          ZonedDateTime.of(Objects.requireNonNull(date), LocalTime.parse(start), SEOUL);
      return new Window(s.toInstant(), s.plusHours(1).toInstant());
    }
    ZonedDateTime s = now.atZone(SEOUL).truncatedTo(ChronoUnit.HOURS);
    return new Window(s.toInstant(), s.plusHours(1).toInstant());
  }

  /** 결제창 유효 시각 = 자원 만료와 가격 동결 중 이른 것(결제 명세 §8, 흐름 §1.1 "더 이른 만료"). */
  public static Instant paymentDeadline(
      @Nullable Instant resourceExpiresAt, Instant quoteExpiresAt) {
    return resourceExpiresAt == null || quoteExpiresAt.isBefore(resourceExpiresAt)
        ? quoteExpiresAt
        : resourceExpiresAt;
  }

  /** 정본이 준 만료 시각들 중 가장 이른 것. */
  public static @Nullable Instant earliest(@Nullable Instant a, @Nullable Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isBefore(b) ? a : b;
  }

  /**
   * 결제 시도 상태 → 만료 처리 판단. 승인(이후 취소 포함)이면 보존, 불명·불일치면 해제 금지(불명 추적), 그 밖(CREATED·PENDING·FAILED)은 해제 —
   * payment 는 자원 만료 뒤 승인 API 를 부르지 않는다.
   */
  public enum PaymentVerdict {
    APPROVED,
    UNRESOLVED,
    NOT_APPROVED
  }

  public static PaymentVerdict verdict(@Nullable String paymentState) {
    if (paymentState == null) {
      return PaymentVerdict.NOT_APPROVED;
    }
    return switch (paymentState) {
      case "APPROVED", "PARTIALLY_CANCELLED", "CANCELLED" -> PaymentVerdict.APPROVED;
      case "UNKNOWN", "MISMATCH" -> PaymentVerdict.UNRESOLVED;
      default -> PaymentVerdict.NOT_APPROVED;
    };
  }
}
