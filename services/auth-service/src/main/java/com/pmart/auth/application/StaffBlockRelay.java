package com.pmart.auth.application;

import com.pmart.auth.infrastructure.KeycloakAdminClient;
import com.pmart.auth.infrastructure.KeycloakAdminClient.AdminEvent;
import com.pmart.auth.infrastructure.KeycloakAdminClient.StaffUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keycloak 직원 차단 중계(ADR-0054 ⑤ 결정 M). Keycloak 은 Kafka 로 사건을 내지 않으므로, 관리 기록(admin events)을 주기적으로 읽어
 * 직원 계정이 막힌 일을 {@code SubjectBlocked} 로 대신 낸다 — 게이트웨이가 그 직원이 이미 받은 access token 을 만료 전에 막는다.
 *
 * <ul>
 *   <li>사용자 수정({@code UPDATE users/<id>}) 뒤 그 사용자가 비활성이면 {@code ACCOUNT_DISABLED}.
 *   <li>"세션 로그아웃"({@code ACTION users/<id>/logout}) → {@code SESSIONS_REVOKED}.
 *   <li>비밀번호 재설정({@code users/<id>/reset-password}) → {@code PASSWORD_RESET}.
 *   <li>사용자 삭제는 운영자 ID 를 알 수 없어 막지 못한다 — 경고 로그. 직원 계정은 지우지 않고 비활성화한다(결정 L 운영 규칙).
 * </ul>
 *
 * 차단 시각 = 관리 기록 시각. 같은 기록은 한 번만 낸다: 표 {@code staff_admin_event_seen} 에 기록 키를 넣고(겹치면 건너뜀) 같은 트랜잭션에서
 * Outbox 로 발행한다 — 인스턴스가 여럿이어도, 다시 읽어도 한 번. Keycloak 이 응답하지 않으면 다음 주기에 다시 읽는다(놓친 기록은 {@code
 * lookback} 안이면 다시 잡힌다).
 */
public class StaffBlockRelay {

  private static final Logger log = LoggerFactory.getLogger(StaffBlockRelay.class);
  private static final Pattern USER_PATH =
      Pattern.compile("^users/([0-9a-fA-F-]{8,64})(?:/(logout|reset-password))?$");

  private final KeycloakAdminClient keycloak;
  private final SubjectBlocks blocks;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final String staffIssuer;
  private final Duration lookback;
  private final Clock clock;

  public StaffBlockRelay(
      KeycloakAdminClient keycloak,
      SubjectBlocks blocks,
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      String staffIssuer,
      Duration lookback,
      Clock clock) {
    this.keycloak = keycloak;
    this.blocks = blocks;
    this.jdbc = jdbc;
    this.tx = tx;
    this.staffIssuer = staffIssuer;
    this.lookback = lookback;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.auth.staff-relay.poll-interval:PT10S}",
      initialDelayString = "${pmart.auth.staff-relay.initial-delay:PT30S}")
  public void scheduledPoll() {
    try {
      poll();
    } catch (RuntimeException e) {
      // Keycloak 장애·네트워크 — 다음 주기에 다시. 원문 응답·토큰은 남기지 않는다
      log.warn("staff block relay poll failed: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * 한 번 읽고 반영한다.
   *
   * @return 이번에 낸 차단 사건 수
   */
  public int poll() {
    Instant since = clock.instant().minus(lookback);
    int published = 0;
    for (AdminEvent e : keycloak.userEventsSince(since)) {
      @Nullable String reason = null;
      Matcher m = USER_PATH.matcher(e.resourcePath());
      if (!m.matches()) {
        continue;
      }
      String userId = m.group(1);
      String action = m.group(2);
      if ("logout".equals(action)) {
        reason = "SESSIONS_REVOKED";
      } else if ("reset-password".equals(action)) {
        reason = "PASSWORD_RESET";
      } else if ("DELETE".equals(e.operationType())) {
        if (markSeen(e)) {
          log.warn(
              "staff user deleted in Keycloak — cannot block its tokens (operator id unknown); disable"
                  + " instead of deleting. eventKey={}",
              e.key());
        }
        continue;
      } else if (!"UPDATE".equals(e.operationType())) {
        continue;
      }
      Optional<StaffUser> user = keycloak.user(userId);
      if (reason == null) {
        if (user.isEmpty() || user.get().enabled()) {
          markSeen(e); // 비활성이 아닌 수정(이름·역할 등) — 다시 보지 않는다
          continue;
        }
        reason = "ACCOUNT_DISABLED";
      }
      String operatorId = user.map(StaffUser::operatorId).orElse(null);
      if (operatorId == null) {
        if (markSeen(e)) {
          log.warn("staff user has no operator id attribute — not blocked. eventKey={}", e.key());
        }
        continue;
      }
      if (publishOnce(e, operatorId, reason)) {
        published++;
        log.info(
            "staff subject blocked from Keycloak admin event reason={} eventKey={}",
            reason,
            e.key());
      }
    }
    return published;
  }

  private boolean publishOnce(AdminEvent e, String operatorId, String reason) {
    Boolean done =
        tx.execute(
            s -> {
              if (!insertSeen(e)) {
                return false;
              }
              blocks.blockForIssuer(
                  staffIssuer, operatorId, "OPERATOR", e.time().toEpochMilli(), e.time(), reason);
              return true;
            });
    return Boolean.TRUE.equals(done);
  }

  private boolean markSeen(AdminEvent e) {
    return Boolean.TRUE.equals(tx.execute(s -> insertSeen(e)));
  }

  private boolean insertSeen(AdminEvent e) {
    return jdbc.update(
            "INSERT INTO staff_admin_event_seen (event_key, event_time, seen_at) VALUES (?, ?, ?)"
                + " ON CONFLICT (event_key) DO NOTHING",
            e.key(),
            java.sql.Timestamp.from(e.time()),
            java.sql.Timestamp.from(clock.instant()))
        == 1;
  }

  /** 보관 기간(lookback 의 두 배)이 지난 기록 키를 지운다 — 그보다 오래된 관리 기록은 다시 읽지 않는다. */
  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
  public void prune() {
    try {
      jdbc.update(
          "DELETE FROM staff_admin_event_seen WHERE event_time < ?",
          java.sql.Timestamp.from(clock.instant().minus(lookback.multipliedBy(2))));
    } catch (RuntimeException e) {
      log.warn("staff block relay prune failed: {}", e.getClass().getSimpleName());
    }
  }
}
