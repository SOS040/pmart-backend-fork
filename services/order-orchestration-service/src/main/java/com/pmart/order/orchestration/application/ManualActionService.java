package com.pmart.order.orchestration.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.infrastructure.ManualActionStore;
import com.pmart.order.orchestration.infrastructure.ManualActionStore.ActionRow;
import com.pmart.order.orchestration.infrastructure.ManualActionStore.StuckRow;
import com.pmart.order.orchestration.infrastructure.SagaStore.SagaRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 운영자 수동 개입(BO-12, 2026-11-16 사용자 결정 — 추천대로, 계약 0.2.0). 권한(OPERATOR_ADMIN)은 backoffice-bff 가 검사하고
 * 여기는 서비스 간 경로다.
 *
 * <ul>
 *   <li>멈춘 사가 목록: 네 사가 표의 ESCALATED·RECONCILIATION_REQUIRED·UNKNOWN_TRACKING 중 처리 완료 표시 전.
 *   <li>REQUERY_RESULT(승인 없음): 결제 진입 사가의 결제 확인 대기(WAITING_RESULT·UNKNOWN_TRACKING)만 — {@link
 *       CheckoutSagaService#advance} 를 바로 한 번(sweep 을 당겨 하는 것). 업무 거절로 멈춘 사가는 같은 요청을 다시 보내도 같은 거절이라
 *       주지 않는다.
 *   <li>MARK_RESOLVED(승인 없음, 사유 필수): ESCALATED·RECONCILIATION_REQUIRED 를 "사람이 밖에서 해결"로 표시 — 사가
 *       상태·자원은 그대로, 목록에서만 빠진다.
 *   <li>RESEND_NOTIFICATION·EXECUTE_COMPENSATION 은 승인 필요·아직 없음(422 UNSUPPORTED_ACTION).
 * </ul>
 */
@Service
public class ManualActionService {

  private static final Logger log = LoggerFactory.getLogger(ManualActionService.class);

  public static final String REQUERY = "REQUERY_RESULT";
  public static final String RESOLVE = "MARK_RESOLVED";

  public record Page(List<StuckRow> items, @Nullable String nextCursor) {}

  private final ManualActionStore store;
  private final CheckoutSagaService checkout;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public ManualActionService(
      ManualActionStore store,
      CheckoutSagaService checkout,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.checkout = checkout;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public Page stuck(@Nullable String cursor, int size) {
    int offset = offset(cursor);
    int n = Math.min(Math.max(size, 1), 200);
    List<StuckRow> rows = store.stuck(offset, n + 1);
    boolean more = rows.size() > n;
    return new Page(more ? rows.subList(0, n) : rows, more ? String.valueOf(offset + n) : null);
  }

  /** 그 사가에 의미 있는 조치. */
  public static List<String> allowedActions(StuckRow s) {
    if ("S1_CHECKOUT".equals(s.sagaType()) && "UNKNOWN_TRACKING".equals(s.state())) {
      return List.of(REQUERY);
    }
    if ("ESCALATED".equals(s.state()) || "RECONCILIATION_REQUIRED".equals(s.state())) {
      return List.of(RESOLVE);
    }
    return List.of();
  }

  /** 조치 실행. 결과 기록(같은 키는 같은 결과). */
  public ActionRow act(
      String sourceSystem,
      String requestId,
      String sagaId,
      String action,
      String operatorId,
      String reason) {
    if (!REQUERY.equals(action) && !RESOLVE.equals(action)) {
      throw new PmartException(
          "UNSUPPORTED_ACTION",
          HttpStatus.UNPROCESSABLE_CONTENT,
          action + " 는 아직 없습니다(승인이 필요한 조치 — 재통지·보상 실행).",
          false,
          List.of());
    }
    if (reason == null || reason.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "reason(조치 사유)이 필요합니다.");
    }
    if (operatorId == null || operatorId.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "operatorId 가 필요합니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "manualAction:" + sagaId, operatorId),
        List.of(action, reason),
        String.class,
        () -> {
          Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          String summary =
              REQUERY.equals(action)
                  ? requery(sagaId)
                  : resolve(sagaId, operatorId, reason.strip(), requestId, now);
          store.insertAction(
              new ActionRow(
                  requestId,
                  sagaId,
                  action,
                  operatorId,
                  reason.strip(),
                  "SUCCEEDED",
                  summary,
                  now,
                  clock.instant().truncatedTo(ChronoUnit.MICROS)));
          log.info("수동 개입 {} 사가 {} by {} — {}", action, sagaId, operatorId, summary);
          return requestId;
        });
    return store.action(sagaId, requestId).orElseThrow();
  }

  public ActionRow get(String sagaId, String requestId) {
    return store
        .action(sagaId, requestId)
        .orElseThrow(() -> PmartException.notFound("MANUAL_ACTION_NOT_FOUND", "수동 조치 기록이 없습니다."));
  }

  private String requery(String sagaId) {
    SagaRow s =
        checkout
            .find(sagaId)
            .orElseThrow(
                () -> unprocessable("NOT_REQUERYABLE", "다시 확인은 결제 진입 사가의 결제 확인 대기에만 있습니다."));
    if (!"UNKNOWN_TRACKING".equals(s.state()) && !"WAITING_RESULT".equals(s.state())) {
      throw unprocessable("NOT_REQUERYABLE", "결제 확인 대기 상태가 아닙니다(지금 " + s.state() + ").");
    }
    checkout.advance(sagaId);
    String after = checkout.find(sagaId).map(SagaRow::state).orElse("?");
    return "결제 원결과 다시 확인 — " + s.state() + " → " + after;
  }

  private String resolve(String sagaId, String by, String reason, String requestId, Instant now) {
    StuckRow s =
        store
            .stuckOne(sagaId)
            .orElseThrow(() -> unprocessable("NOT_RESOLVABLE", "멈춘 사가가 아니거나 이미 처리 완료로 표시됐습니다."));
    if (!allowedActions(s).contains(RESOLVE)) {
      throw unprocessable(
          "NOT_RESOLVABLE", "이 상태(" + s.state() + ")는 처리 완료로 표시하지 않습니다 — 다시 확인을 쓰세요.");
    }
    store.resolve(sagaId, by, reason, requestId, now);
    return "처리 완료 표시(" + s.sagaType() + " " + s.state() + ", 사가 상태·자원은 그대로)";
  }

  private static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      int n = Integer.parseInt(cursor);
      if (n < 0 || n > 100_000) {
        throw new NumberFormatException();
      }
      return n;
    } catch (NumberFormatException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
  }

  private static PmartException unprocessable(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }
}
