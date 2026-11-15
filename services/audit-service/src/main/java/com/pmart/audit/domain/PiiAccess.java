package com.pmart.audit.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 개인정보 열람·다운로드 기록 한 건(BO-25). 추가만 — 고치거나 지우지 않는다. 원문 개인정보는 담지 않는다(subjectRef 는 참조, fields 는 항목 코드).
 *
 * <p>{@link #toString()} 은 사유(purpose)를 숨긴다 — 직원이 쓴 글이라 개인정보가 섞일 수 있어 로그에 남기지 않는다.
 */
public record PiiAccess(
    Instant occurredAt,
    String actorId,
    String actorType,
    @Nullable String actorOnBehalfOf,
    @Nullable String actorRoleGrantRef,
    List<String> actorRoles,
    List<String> actorCenterScope,
    String subjectType,
    String subjectRef,
    String accessType,
    List<String> fields,
    String purpose,
    @Nullable String screen,
    @Nullable String caseRef,
    int recordCount,
    @Nullable String traceId) {

  public PiiAccess {
    actorRoles = List.copyOf(actorRoles);
    actorCenterScope = List.copyOf(actorCenterScope);
    fields = List.copyOf(fields);
  }

  /** 기록 요청 검사. 문제가 있으면 사유, 없으면 null. (계약 검증 뒤에도 남는 업무 조건: 공백만인 사유·항목·주체) */
  public @Nullable String problem() {
    if (purpose.isBlank()) {
      return "purpose(열람 사유)가 필요합니다.";
    }
    if (subjectRef.isBlank()) {
      return "subjectRef 가 필요합니다.";
    }
    if (actorId.isBlank()) {
      return "actor.actorId 가 필요합니다.";
    }
    if (fields.isEmpty() || fields.stream().anyMatch(String::isBlank)) {
      return "fields 는 빈 값 없이 하나 이상이어야 합니다.";
    }
    if (recordCount < 1) {
      return "recordCount 는 1 이상이어야 합니다.";
    }
    return null;
  }

  /** 멱등 비교용 본문 — 재시도마다 달라지는 시도 정보(occurredAt·traceId)는 뺀다. 같은 키로 같은 열람을 다시 보내면 처음 기록을 돌려준다. */
  public PiiAccess forIdempotency() {
    return new PiiAccess(
        Instant.EPOCH,
        actorId,
        actorType,
        actorOnBehalfOf,
        actorRoleGrantRef,
        actorRoles,
        actorCenterScope,
        subjectType,
        subjectRef,
        accessType,
        fields,
        purpose,
        screen,
        caseRef,
        recordCount,
        null);
  }

  @Override
  public String toString() {
    return "PiiAccess[actorId="
        + actorId
        + ", subjectType="
        + subjectType
        + ", accessType="
        + accessType
        + ", fields="
        + fields
        + "]";
  }
}
