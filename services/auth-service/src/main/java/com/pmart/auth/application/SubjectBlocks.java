package com.pmart.auth.application;

import com.pmart.auth.infrastructure.keys.AuthProperties;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 주체 차단 사건 {@code SubjectBlocked}(ADR-0054 ⑤, 계약 {@code contracts/events/SubjectBlocked.v1.json}).
 * 게이트웨이가 받아 Redis 차단 목록에 넣고, 이 주체가 {@code blockedBefore} 이전에 받은 access token 을 만료 전에 401 {@code
 * SUBJECT_BLOCKED} 로 막는다. 세션 폐기(refresh 거부)는 지금처럼 이 서비스가 한다 — 사건은 이미 나간 access token 용이다.
 *
 * <p>업무 변경과 같은 트랜잭션 안에서 부른다(Outbox).
 */
@Component
public class SubjectBlocks {

  public static final String TOPIC = "platform.subject-block.v1";

  private final EventPublisher events;
  private final String issuer;

  public SubjectBlocks(EventPublisher events, AuthProperties auth) {
    this.events = events;
    this.issuer = auth.issuer();
  }

  /**
   * @param aggregateVersion 계정의 바뀐 뒤 버전(같은 주체 사건의 순서 판정용)
   * @param reason 계약의 {@code reason} 코드
   */
  public void block(
      String subject,
      String userType,
      long aggregateVersion,
      Instant blockedBefore,
      String reason,
      String actorOperatorId) {
    publish(
        issuer,
        subject,
        userType,
        aggregateVersion,
        blockedBefore,
        reason,
        new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, actorOperatorId));
  }

  /**
   * 다른 발급자의 주체(Keycloak 직원 — 중계 {@link StaffBlockRelay})를 대신 막는다. {@code issuer} 는 그 발급자 토큰의 {@code
   * iss} 그대로.
   */
  public void blockForIssuer(
      String issuer,
      String subject,
      String userType,
      long aggregateVersion,
      Instant blockedBefore,
      String reason) {
    publish(
        issuer,
        subject,
        userType,
        aggregateVersion,
        blockedBefore,
        reason,
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "keycloak-admin-events"));
  }

  private void publish(
      String issuer,
      String subject,
      String userType,
      long aggregateVersion,
      Instant blockedBefore,
      String reason,
      EventEnvelope.Actor actor) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("issuer", issuer);
    payload.put("subject", subject);
    payload.put("userType", userType);
    payload.put("blockedBefore", blockedBefore.toString());
    payload.put("reason", reason);
    // 토픽 = 집합 이름 기본 토픽(pmart.messaging.domain=platform → platform.subject-block.v1 = TOPIC)
    events.publish(
        "SubjectBlocked",
        1,
        "SubjectBlock",
        subject, // 파티션 키 = 주체 → 같은 주체의 사건 순서 유지
        aggregateVersion,
        actor,
        payload);
  }
}
