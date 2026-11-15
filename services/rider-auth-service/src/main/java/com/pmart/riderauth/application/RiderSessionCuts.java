package com.pmart.riderauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.riderauth.infrastructure.DispatchClient;
import com.pmart.riderauth.infrastructure.RiderAuthStore;
import com.pmart.riderauth.infrastructure.SessionCutStore;
import com.pmart.riderauth.infrastructure.SessionCutStore.CutRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정지·계약 종료 라이더 로그인 끊기(ADR-0054 ⑤ 라이더 정지, 2026-11-15 사용자 "추천대로" — <b>진행 중 배송이 끝난 뒤 끊는다</b>, 신규배차 제한은
 * 끊지 않는다).
 *
 * <ol>
 *   <li>rider {@code RiderRestricted}(delivery.rider.v1) 가 SUSPENDED·TERMINATED 면 PENDING 사본. 그
 *       밖(NORMAL· DISPATCH_RESTRICTED)이면 LIFTED. aggregateVersion(라이더 행 버전)이 지금 사본보다 낮거나 같으면 버린다.
 *   <li>{@link #sweep}({@code pmart.rider-auth.session-cut-interval} 1분): PENDING 마다 dispatch 에 맡은
 *       배송을 묻고, 없으면 모든 세션을 끊고 {@code SubjectBlocked}(RIDER_RESTRICTED, 주체 전체 — 이미 나간 access token 도
 *       게이트웨이에서 바로 401) → DONE. 기한이 지난 제한이면 끊지 않고 LIFTED. dispatch 를 못 부르면 다음 점검에 다시.
 *   <li>DONE 이고 제한이 아직 유효하면 새 로그인을 거절한다({@link #loginBlocked}). 기다리는 동안(PENDING)은 로그인해도 된다 — 픽업한
 *       배송을 끝내야 하므로(사용자 2026-11-14 "픽업한 배송은 끝까지 전달").
 * </ol>
 */
@Service
public class RiderSessionCuts {

  private static final Logger log = LoggerFactory.getLogger(RiderSessionCuts.class);

  public static final Set<String> TYPES = Set.of("RiderRestricted");
  static final Set<String> CUT = Set.of("SUSPENDED", "TERMINATED");

  private final SessionCutStore cuts;
  private final RiderAuthStore sessions;
  private final DispatchClient dispatch;
  private final SessionBlocks blocks;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RiderSessionCuts(
      SessionCutStore cuts,
      RiderAuthStore sessions,
      DispatchClient dispatch,
      SessionBlocks blocks,
      TransactionTemplate tx,
      Clock clock) {
    this.cuts = cuts;
    this.sessions = sessions;
    this.dispatch = dispatch;
    this.blocks = blocks;
    this.tx = tx;
    this.clock = clock;
  }

  /** inbox 트랜잭션 안. */
  public void on(EventEnvelope e) {
    String riderId = e.payload().path("riderId").asString("");
    String restriction = e.payload().path("restriction").asString("");
    if (riderId.isBlank() || restriction.isBlank()) {
      log.warn("RiderRestricted {} 값이 모자라 버림", e.eventId());
      return;
    }
    String to = e.payload().path("to").asString(null);
    Instant until = to == null || to.isBlank() ? null : Instant.parse(to);
    Instant now = clock.instant();
    Optional<CutRow> cur = cuts.lock(riderId);
    if (cur.isPresent() && cur.get().riderVersion() >= e.aggregateVersion()) {
      return; // 늦게 온 옛 사건
    }
    if (!CUT.contains(restriction)) {
      cuts.upsert(riderId, restriction, until, e.aggregateVersion(), "LIFTED", now, null);
      log.info("라이더 {} 제한 {} — 로그인 끊기 대상 아님", riderId, restriction);
      return;
    }
    // 이미 끊었으면(DONE) 단계만 바꾸고 그대로 막는다(일시 중지 → 계약 종료 등)
    boolean done = cur.isPresent() && "DONE".equals(cur.get().state());
    cuts.upsert(
        riderId,
        restriction,
        until,
        e.aggregateVersion(),
        done ? "DONE" : "PENDING",
        done ? cur.get().requestedAt() : now,
        done ? cur.get().cutAt() : null);
    log.info("라이더 {} {} — 진행 중 배송이 끝나면 로그인을 끊는다", riderId, restriction);
  }

  /** 기다리는 라이더를 점검해 맡은 배송이 없으면 끊는다. */
  @Scheduled(
      fixedDelayString = "${pmart.rider-auth.session-cut-interval:PT1M}",
      initialDelayString = "${pmart.rider-auth.session-cut-interval:PT1M}")
  public void sweep() {
    for (String riderId : cuts.pending(100)) {
      try {
        check(riderId);
      } catch (RuntimeException ex) {
        log.warn("라이더 {} 로그인 끊기 점검 실패 — 다음에 다시: {}", riderId, ex.toString());
      }
    }
  }

  /** 한 라이더 점검(시험에서도 부른다). 끊었으면 true. */
  public boolean check(String riderId) {
    Instant now = clock.instant();
    CutRow before = cuts.find(riderId).orElse(null);
    if (before == null || !"PENDING".equals(before.state())) {
      return false;
    }
    if (expired(before, now)) {
      tx.executeWithoutResult(s -> lift(riderId, before.riderVersion()));
      return false;
    }
    List<String> active = dispatch.activeDeliveries(riderId); // 트랜잭션 밖(외부 호출)
    if (!active.isEmpty()) {
      cuts.checked(riderId, now);
      return false;
    }
    Boolean cut =
        tx.execute(
            s -> {
              CutRow r = cuts.lock(riderId).orElse(null);
              // 조회하는 사이 풀리거나 바뀌었으면 이번에는 끊지 않는다
              if (r == null
                  || !"PENDING".equals(r.state())
                  || r.riderVersion() != before.riderVersion()) {
                return false;
              }
              Instant at = clock.instant();
              sessions.revokeAllSessions(riderId, "RIDER_RESTRICTED", at);
              blocks.block(riderId, null, at, "RIDER_RESTRICTED");
              cuts.state(riderId, "DONE", at);
              return true;
            });
    if (Boolean.TRUE.equals(cut)) {
      log.info("라이더 {} 진행 중 배송 없음 — 모든 세션을 끊음({})", riderId, before.restriction());
    }
    return Boolean.TRUE.equals(cut);
  }

  /** 끊긴(DONE) 라이더의 제한이 아직 유효하면 true — 새 로그인 거절. */
  public boolean loginBlocked(String riderId) {
    Instant now = clock.instant();
    return cuts.find(riderId).filter(r -> "DONE".equals(r.state()) && !expired(r, now)).isPresent();
  }

  private void lift(String riderId, long version) {
    cuts.lock(riderId)
        .filter(r -> "PENDING".equals(r.state()) && r.riderVersion() == version)
        .ifPresent(r -> cuts.state(riderId, "LIFTED", null));
  }

  private static boolean expired(CutRow r, Instant now) {
    @Nullable Instant until = r.restrictionUntil();
    return until != null && !until.isAfter(now);
  }
}
