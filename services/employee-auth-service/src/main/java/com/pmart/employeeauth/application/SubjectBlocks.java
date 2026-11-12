package com.pmart.employeeauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 직원 즉시 차단 사건 {@code SubjectBlocked}(ADR-0054 ⑤·ADR-0056 결정 8, 계약 {@code
 * contracts/events/SubjectBlocked.v1.json}, 토픽 platform.subject-block.v1). 게이트웨이가 Redis 차단 목록에 넣어 이
 * 직원이 {@code blockedBefore} 전에 받은 직원 토큰(15분)을 바로 401 {@code SUBJECT_BLOCKED} 로 막는다. 세션(refresh) 종료는
 * 이 서비스가 따로 한다. 업무 변경과 같은 트랜잭션(outbox).
 */
@Component
public class SubjectBlocks {

  private final EventPublisher events;
  private final String issuer;

  public SubjectBlocks(EventPublisher events, EmployeeAuthProperties props) {
    this.events = events;
    this.issuer = props.issuer();
  }

  /**
   * @param aggregateVersion 직원 계정의 바뀐 뒤 버전(같은 주체 사건 순서용)
   * @param actor 누가(관리 경로면 관리자 운영자, hr-service 사건이면 SYSTEM)
   */
  public void block(
      String operatorId, long aggregateVersion, Instant blockedBefore, EventEnvelope.Actor actor) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("issuer", issuer);
    payload.put("subject", operatorId);
    payload.put("userType", "OPERATOR");
    payload.put("blockedBefore", blockedBefore.toString());
    payload.put("reason", "ACCOUNT_DISABLED");
    events.publish(
        "SubjectBlocked",
        1,
        "SubjectBlock",
        operatorId, // 파티션 키 = 주체
        aggregateVersion,
        actor,
        payload);
  }
}
