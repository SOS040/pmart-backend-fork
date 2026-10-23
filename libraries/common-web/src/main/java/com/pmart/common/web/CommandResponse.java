package com.pmart.common.web;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 명령 API 공통 응답 (contracts/openapi/_common.yaml CommandResponse).
 *
 * @param status 접수/반영/거절/불명
 * @param requestId 요청 멱등 키
 * @param resultId 소유 서비스가 발급한 결과 식별자 (commitId, reservationId, holdId …)
 * @param appliedQty 수량 명령일 때 실제 반영 수량
 * @param version 대상 aggregate의 새 버전
 * @param retryable 같은 요청을 재시도해도 되는지
 * @param nextAction 호출자가 다음에 할 일 (QUERY_RESULT, RE_QUOTE, WAIT_FENCE …)
 * @param occurredAt 처리 시각(UTC)
 */
public record CommandResponse(
    CommandStatus status,
    String requestId,
    @Nullable String resultId,
    @Nullable Integer appliedQty,
    @Nullable Integer version,
    boolean retryable,
    @Nullable String nextAction,
    Instant occurredAt) {

  public CommandResponse {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  public static CommandResponse applied(String requestId, String resultId, int version) {
    return new CommandResponse(
        CommandStatus.APPLIED, requestId, resultId, null, version, false, null, Instant.now());
  }

  public static CommandResponse applied(
      String requestId, String resultId, int appliedQty, int version) {
    return new CommandResponse(
        CommandStatus.APPLIED,
        requestId,
        resultId,
        appliedQty,
        version,
        false,
        null,
        Instant.now());
  }

  public static CommandResponse accepted(String requestId, String resultId, String nextAction) {
    return new CommandResponse(
        CommandStatus.ACCEPTED, requestId, resultId, null, null, false, nextAction, Instant.now());
  }

  public static CommandResponse rejected(String requestId, String nextAction) {
    return new CommandResponse(
        CommandStatus.REJECTED, requestId, null, null, null, false, nextAction, Instant.now());
  }

  /** 하위 의존 응답 불명. 호출자는 nextAction(기본 QUERY_RESULT)에 따라 원결과를 조회한다. */
  public static CommandResponse unknown(String requestId) {
    return new CommandResponse(
        CommandStatus.UNKNOWN_RESULT,
        requestId,
        null,
        null,
        null,
        true,
        "QUERY_RESULT",
        Instant.now());
  }
}
