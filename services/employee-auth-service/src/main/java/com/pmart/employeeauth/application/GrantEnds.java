package com.pmart.employeeauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 권한 조기 종료 사건 {@code GrantEnded}(EA-1, 2026-11-17 — 계약 {@code contracts/events/GrantEnded.v1.json},
 * 토픽 platform.auth.v1, {@code GrantApplied} 와 같은 곳·같은 aggregate). grant 가 승인한 기간보다 일찍 끝날 때 grant 마다
 * 한 번 — 회수(REVOKED)·소속 변경으로 이전 센터 권한 종료(AFFILIATION_CHANGED). mfc-capacity 가 그 센터 작업자 권한 기간 끝을
 * {@code endedAt} 으로 당긴다(구독 예정). 업무 변경과 같은 트랜잭션(outbox). 회수 사유 글은 싣지 않는다(DB 에만).
 */
@Component
public class GrantEnds {

  public static final String REVOKED = "REVOKED";
  public static final String AFFILIATION_CHANGED = "AFFILIATION_CHANGED";

  /** GrantApplied 가 1 — grant 하나는 한 번만 일찍 끝난다(회수·소속 변경 모두 아직 회수 전인 grant 만). */
  static final long AGGREGATE_VERSION = 2;

  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public GrantEnds(EventPublisher events, EventEnvelopeCodec codec) {
    this.events = events;
    this.codec = codec;
  }

  /**
   * @param endedAt 이 시각부터 무효(반열림 끝)
   * @param actor 누가(회수면 회수한 운영자, 소속 변경이면 SYSTEM hr-service)
   */
  public void ended(
      String grantId,
      String approvalId,
      String operatorId,
      String role,
      List<String> centers,
      Instant endedAt,
      String reason,
      EventEnvelope.Actor actor) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("grantId", grantId);
    payload.put("approvalRef", Map.of("approvalId", approvalId));
    payload.put("operatorId", operatorId);
    payload.put("roles", List.of(role));
    payload.put("centerScope", centers);
    payload.put("endedAt", endedAt.toString());
    payload.put("reason", reason);
    events.publish(
        codec.create(
            events.sourceSystem(),
            "GrantEnded",
            1,
            "Grant",
            grantId,
            AGGREGATE_VERSION,
            actor,
            EventEnvelope.EventSource.REAL,
            payload),
        GrantApprovalHandler.GRANT_TOPIC);
  }
}
