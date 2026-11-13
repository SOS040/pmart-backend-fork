package com.pmart.auth.application;

import com.pmart.auth.infrastructure.keys.AuthProperties;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 로그아웃·refresh 재사용 때 이미 나간 access token 을 바로 막는 사건(계약 {@code SubjectBlocked}, 토픽
 * platform.subject-block.v1, 2026-11-13). {@code sessionId} 가 있으면 그 세션만(LOGOUT·TOKEN_REUSED), 없으면 그
 * 주체의 모든 토큰(LOGOUT_ALL). 게이트웨이가 Redis 차단 목록에 넣는다. 업무 변경(세션 종료)과 같은 트랜잭션(outbox).
 */
@Component
public class SessionBlocks {

  private final EventPublisher events;
  private final String issuer;

  public SessionBlocks(EventPublisher events, AuthProperties props) {
    this.events = events;
    this.issuer = props.issuer();
  }

  /**
   * @param userType 토큰 typ(CUSTOMER·GUEST)
   */
  public void block(
      String subject,
      @Nullable String sessionId,
      String userType,
      Instant blockedBefore,
      String reason,
      EventEnvelope.Actor actor) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("issuer", issuer);
    payload.put("subject", subject);
    if (sessionId != null) {
      payload.put("sessionId", sessionId);
    }
    payload.put("userType", userType);
    payload.put("blockedBefore", blockedBefore.toString());
    payload.put("reason", reason);
    events.publish("SubjectBlocked", 1, "SubjectBlock", subject, 1L, actor, payload);
  }
}
