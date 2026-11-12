package com.pmart.hr.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.hr.infrastructure.HrStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 요청 권한의 승인 결과를 등록 화면 상태로(ADR-0056 5-4). approval-service {@code ApprovalDecided}(type GRANT, 정본
 * employee-auth-service) 의 행(lineId = 요청 권한 행 ID)을 APPROVED·REJECTED 로. 권한의 효력은
 * employee-auth-service 가 정한다 — 여기는 인사팀이 보는 진행 상태만. inbox 트랜잭션 안.
 */
@Component
public class GrantDecisions {

  private static final Logger log = LoggerFactory.getLogger(GrantDecisions.class);

  private final HrStore store;
  private final Clock clock;

  public GrantDecisions(HrStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public static boolean grantDecision(EventEnvelope e) {
    JsonNode p = e.payload();
    return "ApprovalDecided".equals(e.eventType())
        && "GRANT".equals(p.path("type").asString(null))
        && "employee-auth-service".equals(p.path("targetRef").path("targetService").asString(null));
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String approvalId = p.path("approvalId").asString();
    String operatorId = p.path("targetRef").path("targetId").asString();
    String overall = p.path("decision").asString();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    int updated = 0;
    for (JsonNode line : p.path("lines")) {
      long grantId;
      try {
        grantId = Long.parseLong(line.path("lineId").asString());
      } catch (NumberFormatException ex) {
        continue; // hr-service 가 올린 행이 아님(관리 화면에서 직접 만든 권한 요청 등)
      }
      String decision = line.path("decision").asString(overall);
      String submission = decision.startsWith("APPROVED") ? "APPROVED" : "REJECTED";
      updated += store.markGrantDecided(operatorId, grantId, submission, approvalId, now);
    }
    log.info("권한 승인 결과 반영: approvalId={} operatorId={} {}행", approvalId, operatorId, updated);
  }
}
