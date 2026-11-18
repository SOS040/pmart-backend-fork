package com.pmart.payment.infrastructure.toss;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * [LAN 시험 전용] 시험 결제 자동 승인(2026-11-19, MFC 시뮬레이터 R-21 — 사용자 "응 먼저 회신남기고 제안대로해줘", 계약 0.2.0). {@code
 * pmart.payment.test-auto.enabled=true} 일 때만 {@link TossClient} 대신 쓰인다(운영 설정에는 없다).
 *
 * <p>paymentKey 가 {@code TEST_AUTO_<토스 orderId(= 결제 시도 ID)>} 인 결제만 토스를 부르지 않고 여기서 답한다(취소 응답에도 그
 * orderId 를 실어 원장의 대조를 그대로 통과시키려고 키에 orderId 를 넣는다) — 승인은 DONE(요청 금액 그대로, method TEST_AUTO), 다른
 * orderId 의 키는 거절, 취소는 그 금액만큼 DONE. 그 밖 결제는 모두 진짜 토스로 간다. 앞뒤의 검사(주문·금액·만료)·반영·사건은 실제 흐름 그대로라 결과가 실제
 * 승인과 같다. 조회(get)는 원거래가 없으므로 결과 불명으로 답한다(시험 결제는 불명이 생기지 않아 대사 대상이 되지 않는다).
 */
public class TestAutoTossClient extends TossClient {

  public static final String PREFIX = "TEST_AUTO_";
  static final String METHOD = "TEST_AUTO";

  private static final Logger log = LoggerFactory.getLogger(TestAutoTossClient.class);

  private final Clock clock;

  public TestAutoTossClient(TossProperties props, ObjectMapper mapper, Clock clock) {
    super(props, mapper);
    this.clock = clock;
    log.warn(
        "시험 결제 자동 승인 켜짐(pmart.payment.test-auto.enabled) — {} 로 시작하는 paymentKey 는 토스를 부르지 않는다",
        PREFIX);
  }

  static boolean test(@Nullable String paymentKey) {
    return paymentKey != null && paymentKey.startsWith(PREFIX);
  }

  @Override
  public Result confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
    if (!test(paymentKey)) {
      return super.confirm(paymentKey, orderId, amount, idempotencyKey);
    }
    if (!paymentKey.equals(PREFIX + orderId)) {
      return new Result.Rejected(
          400, "TEST_AUTO_KEY_MISMATCH", "시험 결제 키는 TEST_AUTO_<orderId> 여야 합니다.");
    }
    log.info("시험 결제 자동 승인 orderId={} amount={}", orderId, amount);
    return new Result.Ok(
        200,
        new TossPayment(
            paymentKey,
            orderId,
            "DONE",
            amount,
            amount,
            "KRW",
            METHOD,
            clock.instant(),
            true,
            List.of()));
  }

  @Override
  public Result get(String paymentKey) {
    if (!test(paymentKey)) {
      return super.get(paymentKey);
    }
    return new Result.Unknown(0, "TEST_AUTO_NO_LOOKUP");
  }

  @Override
  public Result cancel(
      String paymentKey,
      String reason,
      long cancelAmount,
      @Nullable Long refundableAmount,
      String idempotencyKey) {
    if (!test(paymentKey)) {
      return super.cancel(paymentKey, reason, cancelAmount, refundableAmount, idempotencyKey);
    }
    Instant now = clock.instant();
    @Nullable Long balance =
        refundableAmount == null ? null : Math.max(0, refundableAmount - cancelAmount);
    String status = balance != null && balance == 0 ? "CANCELED" : "PARTIAL_CANCELED";
    log.info("시험 결제 자동 취소 amount={} balance={}", cancelAmount, balance);
    return new Result.Ok(
        200,
        new TossPayment(
            paymentKey,
            paymentKey.substring(PREFIX.length()),
            status,
            null, // 총액은 원장 값 그대로(COALESCE)
            balance,
            "KRW",
            METHOD,
            null,
            true,
            // 같은 멱등키 재요청은 같은 거래 키 — 원장이 이미 반영한 취소는 다시 맞추지 않는다
            List.of(new TossCancel("TEST_AUTO_TX_" + idempotencyKey, cancelAmount, now, "DONE"))));
  }
}
