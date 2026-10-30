package com.pmart.payment.domain;

import java.util.Set;

/** 결제 시도 세부 단계(결제 명세 §5)와 계약 PaymentState 요약. */
public enum PaymentStage {
  READY("CREATED"),
  AUTH_FAILED("FAILED"),
  CANCELED_BY_USER("FAILED"),
  CONFIRMING("PENDING"),
  CONFIRM_UNKNOWN("UNKNOWN"),
  APPROVED("APPROVED"),
  CONFIRM_FAILED("FAILED"),
  EXPIRED("FAILED"),
  MISMATCH_HOLD("MISMATCH"),
  MANUAL_REVIEW("UNKNOWN"),
  PARTIAL_CANCELED("PARTIALLY_CANCELLED"),
  CANCELED("CANCELLED");

  /** 결제가 끝나 다시 바뀌지 않는 실패 단계. */
  public static final Set<PaymentStage> FAILED_FINAL =
      Set.of(AUTH_FAILED, CANCELED_BY_USER, CONFIRM_FAILED, EXPIRED);

  private final String state;

  PaymentStage(String state) {
    this.state = state;
  }

  /** 계약 PaymentState. */
  public String state() {
    return state;
  }

  public boolean cancellable() {
    return this == APPROVED || this == PARTIAL_CANCELED;
  }
}
