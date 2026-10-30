package com.pmart.delivery.capacity.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.common.web.RequestHeaders;
import com.pmart.delivery.capacity.domain.SlotHold;
import com.pmart.delivery.capacity.domain.SlotRules;
import com.pmart.delivery.capacity.infrastructure.CenterCalendarClient;
import com.pmart.delivery.capacity.infrastructure.SlotHoldStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 슬롯 점유(HoldSlot) — orchestration 자원 확보 사가 S1 d단계(예약 배송), 주문 확정 후 확정, 보상·취소 해제.
 *
 * <ul>
 *   <li>선택 가능 판정은 조회 목록과 같은 규칙({@link SlotRules}): 오늘·내일, 운영 구간 안 정시 1시간, 08시 전 없음, 시작 시각 전.
 *   <li>정원 미설정·캘린더 조회 실패 → 422 CONFIG_REQUIRED(계약: 신규 결제 보류). 0·무제한으로 대체하지 않는다.
 *   <li>같은 슬롯 점유는 슬롯 잠금 행(FOR UPDATE)으로 줄 세운 뒤 센다 — 마지막 1자리에 동시 2건이면 1건만 성공.
 *   <li>주문당 활성 점유 1건(부분 유일 색인이 최종 방어선). S4 교체(newSlotFor)는 아직 받지 않는다(422 UNSUPPORTED_ACTION).
 *   <li>모든 변경은 {@link RequestIdempotency} 안에서: 같은 키·같은 본문은 같은 결과. 캘린더 조회도 그 안에서 하므로 재전송은 시간대가 마감된
 *       뒤에도 기존 점유를 돌려준다.
 * </ul>
 */
@Service
public class SlotHoldService {

  /** 점유 요청(계약 SlotHoldRequest). */
  public record CreateHold(
      String orderId,
      @Nullable String draftId,
      @Nullable String sagaId,
      String mfcId,
      LocalDate date,
      String start,
      String end,
      int ttlSeconds,
      @Nullable String newSlotFor) {}

  public record ConfirmHold(String holdId, String orderId, @Nullable String sagaId) {}

  public record ReleaseHold(
      String holdId, String reason, @Nullable String sagaId, @Nullable String approvalRef) {}

  /** 확정·해제 명령 결과(계약 CommandResponse·HoldCommandResult 의 공통 원천). */
  public record CommandResult(
      String requestId,
      String holdId,
      CommandStatus status,
      SlotHold.State resultingState,
      int version,
      Instant occurredAt) {}

  public static final String CONFIRM = "CONFIRM";
  public static final String RELEASE = "RELEASE";

  private static final Logger log = LoggerFactory.getLogger(SlotHoldService.class);

  private final SlotHoldStore store;
  private final CenterCalendarClient calendars;
  private final CapacityProperties props;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public SlotHoldService(
      SlotHoldStore store,
      CenterCalendarClient calendars,
      CapacityProperties props,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.calendars = calendars;
    this.props = props;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  // ---------- 점유 ----------

  /** 점유. 같은 키 재전송은 기존 점유(현재 상태)를 돌려준다. */
  public SlotHold create(String requestId, String sourceSystem, CreateHold cmd) {
    if (cmd.newSlotFor() != null) {
      throw new PmartException(
          ErrorCode.UNSUPPORTED_ACTION.name(),
          HttpStatus.UNPROCESSABLE_CONTENT,
          "예약 시간 변경(S4, newSlotFor)은 아직 지원하지 않습니다.",
          false,
          List.of());
    }
    String holdId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createSlotHold", actor()),
                cmd,
                String.class,
                () -> hold(cmd, sourceSystem))
            .value();
    return get(holdId);
  }

  private String hold(CreateHold cmd, String sourceSystem) {
    Instant now = clock.instant();
    SlotRules.Window w;
    try {
      w = SlotRules.parseWindow(cmd.date(), cmd.start(), cmd.end());
    } catch (IllegalArgumentException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, e.getMessage());
    }
    if (!SlotRules.bookableDate(cmd.date(), now)) {
      throw notSelectable("예약 슬롯은 오늘·내일(Asia/Seoul)만 선택할 수 있습니다.");
    }
    if (!now.isBefore(w.startAt())) {
      throw notSelectable("선택 마감이 지난 시간대입니다.");
    }
    Integer capacity = props.slotCapacity();
    if (capacity == null) {
      throw PmartException.configRequired("pmart.capacity.slot-capacity");
    }
    CenterCalendarClient.Day day = customerOrderDay(cmd.mfcId(), cmd.date());
    if (!day.open() || !SlotRules.selectable(w, day.ranges(), now)) {
      throw notSelectable("운영 구간 안의 선택 가능한 1시간 시간대가 아닙니다(08시 전 없음).");
    }

    store.expireStaleForOrder(cmd.orderId(), now);
    store
        .activeHoldIdForOrder(cmd.orderId())
        .ifPresent(
            id -> {
              throw holdAlreadyActive(cmd.orderId());
            });

    store.lockSlot(cmd.mfcId(), cmd.date(), w.startMinute());
    int used = store.occupying(cmd.mfcId(), cmd.date(), w.startMinute(), now);
    if (used >= capacity) {
      throw new PmartException(ErrorCode.CAPACITY_EXCEEDED, "시간대 정원이 모두 찼습니다.");
    }
    SlotHold h =
        new SlotHold(
            "SH-" + UUID.randomUUID(),
            1,
            cmd.orderId(),
            cmd.draftId(),
            cmd.sagaId(),
            cmd.mfcId(),
            w,
            SlotHold.State.HELD,
            now,
            now.plus(Duration.ofSeconds(cmd.ttlSeconds())),
            null,
            null,
            null);
    try {
      store.insert(h, sourceSystem);
    } catch (DuplicateKeyException e) {
      // 같은 주문의 다른 키 점유가 동시에 들어와 먼저 반영됨
      throw holdAlreadyActive(cmd.orderId());
    }
    log.info(
        "slot hold {} {} order {} until {}", h.holdId(), h.slotId(), h.orderId(), h.expiresAt());
    return h.holdId();
  }

  /** 계약: 조회 실패도 CONFIG_REQUIRED(신규 결제 보류) — 센터 없음·시간표 미승인·연결 실패·결과 불명 모두. */
  private CenterCalendarClient.Day customerOrderDay(String mfcId, LocalDate date) {
    try {
      return calendars.customerOrderDay(mfcId, date);
    } catch (RuntimeException e) {
      log.warn("center calendar lookup failed for {} {}: {}", mfcId, date, e.getMessage());
      throw new PmartException(
          ErrorCode.CONFIG_REQUIRED,
          "센터 고객 주문 운영 시간표를 확인하지 못했습니다(center." + mfcId + ".timetable.CUSTOMER_ORDER).");
    }
  }

  // ---------- 조회 ----------

  public SlotHold get(String holdId) {
    return store
        .find(holdId, false)
        .orElseThrow(() -> holdNotFound(holdId))
        .observedAt(clock.instant());
  }

  public CommandResult commandResult(String holdId, String kind, String requestId) {
    SlotHoldStore.CommandRow c =
        store
            .findCommand(holdId, kind, requestId)
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        "HOLD_COMMAND_NOT_FOUND", "명령 결과가 없습니다: " + holdId + "/" + requestId));
    return new CommandResult(
        c.requestId(),
        c.holdId(),
        CommandStatus.valueOf(c.status()),
        SlotHold.State.valueOf(c.resultingState()),
        c.version(),
        c.occurredAt());
  }

  // ---------- 확정·해제 ----------

  /** HELD → CONFIRMED. 이미 CONFIRMED 면 멱등 성공, RELEASED·EXPIRED 면 409 INVALID_STATE. */
  public CommandResult confirm(
      String requestId, String sourceSystem, @Nullable Integer expectedVersion, ConfirmHold cmd) {
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "confirmSlotHold", actor()),
            cmd,
            CommandResult.class,
            () -> {
              Instant now = clock.instant();
              SlotHold h =
                  store.find(cmd.holdId(), true).orElseThrow(() -> holdNotFound(cmd.holdId()));
              if (!h.orderId().equals(cmd.orderId())) {
                throw PmartException.invalidState("다른 주문의 슬롯 점유입니다.");
              }
              int version = h.version();
              switch (h.confirmDecision(now)) {
                case INVALID ->
                    throw PmartException.invalidState(
                        "점유가 " + h.stateAt(now) + " 상태라 확정할 수 없습니다. 다시 확보하세요.");
                case APPLY -> {
                  check(expectedVersion, version);
                  version++;
                  store.markConfirmed(h.holdId(), version, now);
                  log.info("slot hold {} confirmed", h.holdId());
                }
                case ALREADY_DONE -> {}
              }
              return record(CONFIRM, requestId, h.holdId(), SlotHold.State.CONFIRMED, version, now);
            })
        .value();
  }

  /** HELD·CONFIRMED → RELEASED(정원 반환). 이미 RELEASED·EXPIRED 면 멱등 성공. OPERATOR 는 approvalRef 필수. */
  public CommandResult release(
      String requestId, String sourceSystem, @Nullable Integer expectedVersion, ReleaseHold cmd) {
    if (SlotHold.approvalMissing(cmd.reason(), cmd.approvalRef())) {
      throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "운영자 해제는 승인 근거(approvalRef)가 필요합니다.");
    }
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "releaseSlotHold", actor()),
            cmd,
            CommandResult.class,
            () -> {
              Instant now = clock.instant();
              SlotHold h =
                  store.find(cmd.holdId(), true).orElseThrow(() -> holdNotFound(cmd.holdId()));
              int version = h.version();
              SlotHold.State resulting = h.stateAt(now);
              if (h.releaseDecision(now) == SlotHold.Decision.APPLY) {
                check(expectedVersion, version);
                version++;
                store.markReleased(h.holdId(), version, now, cmd.reason(), cmd.approvalRef());
                resulting = SlotHold.State.RELEASED;
                log.info("slot hold {} released ({})", h.holdId(), cmd.reason());
              }
              return record(RELEASE, requestId, h.holdId(), resulting, version, now);
            })
        .value();
  }

  private CommandResult record(
      String kind,
      String requestId,
      String holdId,
      SlotHold.State resulting,
      int version,
      Instant now) {
    CommandResult r =
        new CommandResult(requestId, holdId, CommandStatus.APPLIED, resulting, version, now);
    store.insertCommand(
        new SlotHoldStore.CommandRow(
            holdId, kind, requestId, r.status().name(), resulting.name(), version, now));
    return r;
  }

  private static void check(@Nullable Integer expectedVersion, int current) {
    new RequestHeaders(null, null, expectedVersion, null).checkExpectedVersion(current);
  }

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }

  private static PmartException notSelectable(String message) {
    return new PmartException(
        "SLOT_NOT_SELECTABLE", HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  private static PmartException holdAlreadyActive(String orderId) {
    return new PmartException(
        "HOLD_ALREADY_ACTIVE",
        HttpStatus.CONFLICT,
        "주문에 이미 활성 슬롯 점유가 있습니다: " + orderId,
        false,
        List.of());
  }

  private static PmartException holdNotFound(String holdId) {
    return PmartException.notFound("SLOT_HOLD_NOT_FOUND", "슬롯 점유가 없습니다: " + holdId);
  }
}
