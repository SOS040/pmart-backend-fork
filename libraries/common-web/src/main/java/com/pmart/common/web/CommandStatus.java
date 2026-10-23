package com.pmart.common.web;

/**
 * 명령 API 응답 상태 (contracts/openapi/_common.yaml CommandStatus).
 *
 * <p>ACK ≠ 업무 성공. ACCEPTED는 접수만, APPLIED는 정본 반영 완료, REJECTED는 명시적 거절, UNKNOWN_RESULT는 하위 의존 응답 불명으로
 * 호출자가 원결과 조회로만 해소한다.
 */
public enum CommandStatus {
  ACCEPTED,
  APPLIED,
  REJECTED,
  UNKNOWN_RESULT
}
