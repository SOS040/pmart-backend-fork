package com.pmart.payment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 결제 시도 규칙(결제 명세 §3·§5·§7·§9, ADR-0005).
 *
 * <ul>
 *   <li>결제 시도 ID = 토스 orderId: {@code pay-} + 무작위 32자(R5). 받은 쪽은 형식·값을 가공하지 않는다(R6).
 *   <li>승인 성공은 토스 응답 {@code status=DONE} 이고 paymentKey·orderId·금액·통화가 결제 시도와 같을 때만(F6, R3).
 *   <li>결과 불명 재조회: 직후 1회 → 1분 간격 최대 5회 → MANUAL_REVIEW 상신, 이후 15분 간격(OMS §7). 시간 경과만으로 실패 처리하지 않는다.
 *   <li>추가 환불 가능 한도 = 승인액 − 성공 누적 − 진행 중·불명 점유(OMS §10).
 * </ul>
 */
public final class PaymentRules {

  public static final Pattern ORDER_ID = Pattern.compile("^[A-Za-z0-9_=-]{6,64}$");

  /** 재조회 일정. */
  public static final int FAST_RETRIES = 5;

  public static final Duration FAST_INTERVAL = Duration.ofMinutes(1);
  public static final Duration SLOW_INTERVAL = Duration.ofMinutes(15);

  /** 같은 주문에 이것이 있으면 새 결제 시도를 만들지 않는다(R7). */
  public static final Set<PaymentStage> BLOCKS_NEW_ATTEMPT =
      Set.of(
          PaymentStage.CONFIRMING,
          PaymentStage.CONFIRM_UNKNOWN,
          PaymentStage.MISMATCH_HOLD,
          PaymentStage.MANUAL_REVIEW);

  private PaymentRules() {}

  /** 토스 응답 대조(R3). 불일치 필드, 없으면 empty. */
  public static Optional<String> mismatch(
      String expectedOrderId,
      @Nullable String expectedPaymentKey,
      long expectedAmount,
      @Nullable String orderId,
      @Nullable String paymentKey,
      @Nullable Long totalAmount,
      @Nullable String currency) {
    if (!expectedOrderId.equals(orderId)) {
      return Optional.of("ORDER_REF");
    }
    if (expectedPaymentKey != null && !expectedPaymentKey.equals(paymentKey)) {
      return Optional.of("ORDER_REF");
    }
    if (totalAmount == null || totalAmount != expectedAmount) {
      return Optional.of("AMOUNT");
    }
    if (!"KRW".equals(currency)) {
      return Optional.of("CURRENCY");
    }
    return Optional.empty();
  }

  /** 불명 재조회 다음 시각과 상신 여부. count 는 이번까지의 재조회 횟수. */
  public record Next(Instant at, boolean escalate) {}

  public static Next nextCheck(int count, Instant now) {
    if (count < FAST_RETRIES) {
      return new Next(now.plus(FAST_INTERVAL), false);
    }
    return new Next(now.plus(SLOW_INTERVAL), count == FAST_RETRIES);
  }

  /** 남은 환불 가능액. */
  public static long remaining(long approved, long succeeded, long held) {
    return approved - succeeded - held;
  }

  /** 결제창 실패 보고 → 단계. */
  public static PaymentStage authFailureStage(String errorCode) {
    return "PAY_PROCESS_CANCELED".equals(errorCode)
        ? PaymentStage.CANCELED_BY_USER
        : PaymentStage.AUTH_FAILED;
  }

  /** 승인 직전 자원 확인(§8): 만료 시각이 지났으면 승인 API 를 부르지 않는다. */
  public static boolean resourcesExpired(Instant resourceExpiresAt, Instant now) {
    return !now.isBefore(resourceExpiresAt);
  }
}
