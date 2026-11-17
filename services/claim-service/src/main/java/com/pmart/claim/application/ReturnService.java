package com.pmart.claim.application;

import com.pmart.claim.application.ClaimService.ClaimView;
import com.pmart.claim.domain.ClaimRules;
import com.pmart.claim.domain.ReturnRules;
import com.pmart.claim.infrastructure.ClaimStore;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.FeeRow;
import com.pmart.claim.infrastructure.ClaimStore.LineRow;
import com.pmart.claim.infrastructure.ClaimStore.PickupRow;
import com.pmart.claim.infrastructure.ClaimStore.RefundRow;
import com.pmart.claim.infrastructure.Upstreams;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 반품 — 라이더 인계 뒤 회수(CL-1, D-7·ADR-0060 결정 2, 사용자 결정 2026-11-17 "추천대로"). 흐름 정본
 * docs/business/flows/return-exchange-refund.md §6.
 *
 * <ol>
 *   <li>접수: 고객센터(CS_AGENT·OPERATOR_ADMIN)만. order 원장으로 인계 수량 − 진행·완료 반품 수량 심사. 회사 잘못 사유가 있으면 회수비 면제
 *       → 바로 회수 요청, 없으면 회수비 9,000원 결제 대기({@link ReturnFeeService}).
 *   <li>회수: {@code ReturnPickupRequested} → delivery 회수 배송. {@code ReturnPickedUp}·{@code
 *       ReturnDelivered}· {@code DeliveryFailed}(delivery.delivery.v1)로 진행. 첫 실패는 자동 재요청, 그다음 실패는
 *       RECOVERY_FAILED(상담원 재연락).
 *   <li>반입·검수: {@code ReturnItemReceived}(mfc.inbound.v1) 수령 수량, {@code
 *       ReturnInspected}(mfc.quality.v1) 검수 수량마다 원단가 × 수량 − 할인 안분 환불(RESALE·DISPOSE 모두) → payment
 *       부분 취소. 모든 행 검수·환불 완료면 CLOSED.
 * </ol>
 *
 * 추가 결정(2026-11-17 사용자 "추천대로" 1~9): 회수 시도 전체 3번(자동 2 + 상담원 1 — 넘으면 상담원은 취소만), 라이더·우리 잘못
 * (RIDER_INCIDENT)은 횟수에서 빼고 바로 다시 요청, 반품 전체 기한 접수 + 7일(지나면 자동 취소 — {@link #expireOverdue()}), 회수
 * 실패로 취소할 때 낸 회수비는 고객 사유면 그대로·우리 잘못이면 돌려줌·파손·기타는 상담원 판단, 상담원 "받은 만큼만 끝내기"(CLOSE_AS_RECEIVED).
 *
 * <p>사건 반영은 inbox 트랜잭션 안. 같은 반입·검수는 업무 키(return_fact)로 한 번만.
 */
@Service
public class ReturnService {

  private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

  /** 반품 접수 명령(계약 CreateClaimRequest 의 RETURN 부분). */
  public record CreateReturn(
      String orderId,
      List<ClaimRules.Requested> lines,
      String reason,
      @Nullable String reasonDetail,
      String channel,
      @Nullable String faultCode,
      @Nullable String identityMethod,
      @Nullable String identityNote,
      @Nullable String caseRef) {}

  /**
   * 상담원 후속 조치(멱등 비교용 — 경로의 claimId 포함).
   *
   * @param feeRefund CANCEL 만 — 낸 회수비를 돌려주나(파손·기타 실패일 때 상담원 판단, 없으면 규칙 기본). 정해진 규칙과 다르면 422
   */
  public record RecoveryAction(
      String claimId, String action, String note, @Nullable Boolean feeRefund) {}

  public static final String RETRY_PICKUP = "RETRY_PICKUP";
  public static final String CANCEL = "CANCEL";
  public static final String CLOSE_AS_RECEIVED = "CLOSE_AS_RECEIVED";

  private static final Set<String> SYSTEM_REASONS =
      Set.of("FULFILLMENT_REJECTED", ClaimService.OUT_OF_STOCK);

  private final ClaimStore store;
  private final Upstreams upstreams;
  private final RequestIdempotency idempotency;
  private final ClaimEvents events;
  private final ReturnPickups pickups;
  private final ReturnFeeService fees;
  private final RefundService refunds;
  private final TransactionTemplate tx;
  private final ClaimProperties props;
  private final Clock clock;

  public ReturnService(
      ClaimStore store,
      Upstreams upstreams,
      RequestIdempotency idempotency,
      ClaimEvents events,
      ReturnPickups pickups,
      ReturnFeeService fees,
      RefundService refunds,
      TransactionTemplate tx,
      ClaimProperties props,
      Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.idempotency = idempotency;
    this.events = events;
    this.pickups = pickups;
    this.fees = fees;
    this.refunds = refunds;
    this.tx = tx;
    this.props = props;
    this.clock = clock;
  }

  // ---------- 접수 ----------

  public ClaimView create(
      String idempotencyKey, String sourceSystem, PmartPrincipal who, CreateReturn in) {
    requireStaff(who);
    String identity = ReturnRules.identityRejection(in.identityMethod(), in.identityNote());
    if (identity != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, identity);
    }
    if (!ReturnRules.STAFF_CHANNELS.contains(in.channel())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "반품은 고객센터 채널(CS·OPERATOR)로만 접수합니다: " + in.channel());
    }
    if (in.faultCode() != null && !ReturnRules.FAULT_CODES.contains(in.faultCode())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "알 수 없는 면제 사유입니다: " + in.faultCode());
    }
    if (SYSTEM_REASONS.contains(in.reason())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "시스템 전용 사유입니다: " + in.reason());
    }
    long fee = ReturnRules.fee(in.faultCode());
    Duration wait = props.returnFeePaymentWait();
    if (fee > 0 && wait == null) {
      throw PmartException.configRequired("pmart.claim.return-fee-payment-wait");
    }
    Duration deadline = props.returnDeadline();
    if (deadline == null) {
      throw PmartException.configRequired("pmart.claim.return-deadline");
    }
    JsonNode order = upstreams.order(in.orderId());
    if (order == null) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    List<ReturnRules.OrderLine> lines = new ArrayList<>();
    for (JsonNode l : order.path("lines")) {
      lines.add(
          new ReturnRules.OrderLine(
              l.path("orderLineId").asString(),
              l.path("sku").asString(),
              l.path("unitPrice").asLong(),
              l.path("originalQty").asInt(),
              l.path("deliveredQty").asInt(0), // 전달 완료 수량(2026-11-17 — 배달 중 반품 불가)
              l.path("discountShare").asLong(0)));
    }
    String claimId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createReturnClaim", who.subject()),
                in,
                String.class,
                () -> open(who.subject(), in, order, lines, fee, wait, deadline))
            .value();
    return ClaimView.of(store, claimId);
  }

  private String open(
      String staffId,
      CreateReturn in,
      JsonNode order,
      List<ReturnRules.OrderLine> orderLines,
      long fee,
      @Nullable Duration wait,
      Duration deadline) {
    store.lockOrderForReturn(in.orderId());
    List<ReturnRules.Requested> requested =
        in.lines().stream().map(r -> new ReturnRules.Requested(r.orderLineId(), r.qty())).toList();
    try {
      ReturnRules.checkReturnable(orderLines, requested, store.returnOccupied(in.orderId()));
    } catch (ClaimRules.Rejection r) {
      throw new PmartException(
          r.code(), HttpStatus.UNPROCESSABLE_CONTENT, r.getMessage(), false, List.of());
    }
    Map<String, ReturnRules.OrderLine> byLine = new HashMap<>();
    orderLines.forEach(l -> byLine.put(l.orderLineId(), l));
    Instant now = clock.instant();
    String claimId = "CLM-" + UUID.randomUUID();
    long items = 0;
    List<LineRow> rows = new ArrayList<>();
    int n = 1;
    for (ReturnRules.Requested r : requested) {
      ReturnRules.OrderLine l = byLine.get(r.orderLineId());
      items += l.unitPrice() * r.qty();
      rows.add(
          new LineRow(
              claimId + "-L" + n++,
              claimId,
              r.orderLineId(),
              l.sku(),
              r.qty(),
              r.qty(),
              "APPROVED",
              l.unitPrice() * r.qty(),
              l.unitPrice(),
              l.originalQty(),
              l.discountShare(),
              0,
              0,
              0,
              0));
    }
    ClaimRow c =
        new ClaimRow(
            claimId,
            in.orderId(),
            order.path("accountId").asString(),
            "RETURN",
            fee > 0 ? ReturnRules.FEE_PENDING : ReturnRules.IN_PROGRESS,
            in.reason(),
            in.reasonDetail(),
            in.channel(),
            null,
            1,
            order.path("paymentAttemptId").asString(),
            order.path("approvedAmount").asLong(),
            items,
            0,
            0,
            null,
            now,
            now,
            null,
            now,
            in.faultCode(),
            in.identityMethod(),
            in.identityNote(),
            in.caseRef(),
            staffId,
            order.path("mfcId").asString(null),
            null,
            now.plus(deadline));
    store.insert(c, rows);
    events.claimOpened(c, rows);
    if (fee > 0 && wait != null) {
      fees.open(c, fee, now.plus(wait), now);
      log.info("반품 {} 주문 {} 접수 — 회수비 {}원 결제 대기", claimId, in.orderId(), fee);
    } else {
      pickups.request(c, "SYSTEM", now);
      log.info("반품 {} 주문 {} 접수 — 회수비 면제({}), 회수 요청", claimId, in.orderId(), in.faultCode());
    }
    return claimId;
  }

  // ---------- 고객: 회수비 다시 결제 ----------

  public ClaimView retryFee(
      String idempotencyKey, String sourceSystem, PmartPrincipal who, String claimId) {
    if (who.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "고객 본인만 회수비를 결제할 수 있습니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, idempotencyKey, "retryReturnFee", who.subject()),
        claimId,
        String.class,
        () -> {
          fees.retry(claimId, who.subject());
          return claimId;
        });
    return ClaimView.of(store, claimId);
  }

  // ---------- 상담원: 회수 후속 조치 ----------

  public ClaimView recoveryAction(
      String idempotencyKey,
      String sourceSystem,
      PmartPrincipal who,
      RecoveryAction in,
      @Nullable Integer expectedVersion) {
    requireStaff(who);
    if (in.note() == null || in.note().isBlank() || in.note().length() > 500) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "조치 사유(note, 1~500자)가 필요합니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, idempotencyKey, "returnRecoveryAction", who.subject()),
        in,
        String.class,
        () -> {
          ClaimRow c =
              store
                  .lock(in.claimId())
                  .orElseThrow(() -> PmartException.notFound("CLAIM_NOT_FOUND", "클레임이 없습니다."));
          if (!"RETURN".equals(c.type())) {
            throw new PmartException(
                "UNSUPPORTED_ACTION",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "반품(RETURN) 클레임만 회수 조치를 할 수 있습니다.",
                false,
                List.of());
          }
          if (expectedVersion != null && expectedVersion != c.version()) {
            throw PmartException.versionConflict(
                "클레임이 이미 바뀌었습니다(기대 버전 " + expectedVersion + ", 현재 " + c.version() + ").");
          }
          Instant now = clock.instant();
          if (in.feeRefund() != null && !CANCEL.equals(in.action())) {
            throw new PmartException(
                ErrorCode.VALIDATION_FAILED, "feeRefund 는 반품 취소(CANCEL)에만 씁니다.");
          }
          switch (in.action()) {
            case RETRY_PICKUP -> {
              if (!ReturnRules.RECOVERY_FAILED.equals(c.state())) {
                throw PmartException.invalidState("회수 실패(RECOVERY_FAILED) 상태에서만 다시 회수를 요청합니다.");
              }
              if (ReturnRules.pastDeadline(c.returnDeadlineAt(), now)) {
                throw conflict(
                    "RETURN_DEADLINE_PASSED",
                    "반품 기한(" + c.returnDeadlineAt() + ")이 지나 다시 회수를 요청할 수 없습니다 — 반품 취소만 할 수 있습니다.");
              }
              if (!ReturnRules.csRetryAllowed(countedFailures(c.claimId()))) {
                throw conflict(
                    "PICKUP_ATTEMPTS_EXHAUSTED",
                    "회수 시도 "
                        + ReturnRules.MAX_PICKUP_ATTEMPTS
                        + "번(자동 2 + 상담원 1)을 모두 썼습니다 — 반품 취소만 할 수 있습니다.");
              }
              store.updateState(c.claimId(), ReturnRules.IN_PROGRESS, null, null, null, now);
              pickups.request(store.lock(c.claimId()).orElseThrow(), who.subject(), now);
            }
            case CANCEL -> {
              if (!ReturnRules.FEE_PENDING.equals(c.state())
                  && !ReturnRules.RECOVERY_FAILED.equals(c.state())) {
                throw PmartException.invalidState(
                    "회수비 결제 대기·회수 실패 상태에서만 반품을 취소합니다(지금 " + c.state() + ").");
              }
              if (anythingCollected(c.claimId())) {
                throw PmartException.invalidState(
                    "이미 수거·반입된 회수품이 있어 반품을 취소할 수 없습니다 — 받은 만큼만 끝내기(CLOSE_AS_RECEIVED)를 쓰세요.");
              }
              try {
                cancelReturn(c, "CS_CANCELLED", "RETURN_CANCELLED", in.feeRefund(), now);
              } catch (ClaimRules.Rejection r) {
                throw new PmartException(
                    r.code(), HttpStatus.UNPROCESSABLE_CONTENT, r.getMessage(), false, List.of());
              }
            }
            case CLOSE_AS_RECEIVED -> closeAsReceived(c, now);
            default ->
                throw new PmartException(
                    ErrorCode.VALIDATION_FAILED, "알 수 없는 조치입니다: " + in.action());
          }
          store.insertAction(
              "CLA-" + UUID.randomUUID(), c.claimId(), in.action(), who.subject(), in.note(), now);
          log.info("반품 {} 상담원 조치 {} by {}", c.claimId(), in.action(), who.subject());
          return c.claimId();
        });
    return ClaimView.of(store, in.claimId());
  }

  // ---------- 사건 반영(inbox 트랜잭션 안) ----------

  /** delivery.delivery.v1 — 고객 반품 회수 배송(purpose CLAIM_RETURN·claimId)의 생성·수거·반납·실패. 그 밖은 지나간다. */
  public void onDeliveryEvent(EventEnvelope e) {
    JsonNode p = e.payload();
    switch (e.eventType()) {
      case "DeliveryCreated" -> {
        if ("CLAIM_RETURN".equals(p.path("purpose").asString(null))) {
          linkDelivery(p);
        }
      }
      case "ReturnPickedUp", "ReturnDelivered" -> {
        String purpose = p.path("purpose").asString(null);
        if (purpose == null || "CLAIM_RETURN".equals(purpose)) {
          pickupProgress(e.eventType(), p);
        }
      }
      // 회수 실패: delivery 는 고객 반품 회수 실패를 ReturnPickupFailed 로 낸다(DD-3, PC 5 2026-11-17 —
      // claimId·reason·contactAttempts).
      // DeliveryFailed 는 claimId 가 실린 경우만 받는다(같은 처리, 두 번 와도 회수 줄 상태로 한 번만)
      case "ReturnPickupFailed", "DeliveryFailed" -> pickupFailed(p);
      default -> {}
    }
  }

  private void linkDelivery(JsonNode p) {
    String claimId = p.path("claimId").asString(null);
    String deliveryId = p.path("deliveryId").asString(null);
    if (claimId == null || deliveryId == null) {
      return;
    }
    String requestId = p.path("pickupRequestId").asString(null);
    PickupRow pickup =
        requestId != null
            ? store.pickup(requestId).orElse(null)
            : store
                .lastPickup(claimId)
                .filter(x -> x.deliveryId() == null && "REQUESTED".equals(x.state()))
                .orElse(null);
    if (pickup == null) {
      log.warn("반품 {} 회수 배송 {} — 이을 회수 요청이 없음", claimId, deliveryId);
      return;
    }
    store.linkPickupDelivery(pickup.pickupRequestId(), deliveryId, clock.instant());
  }

  private @Nullable PickupRow pickupOf(@Nullable String claimId, @Nullable String deliveryId) {
    if (deliveryId != null) {
      PickupRow byDelivery = store.pickupByDelivery(deliveryId).orElse(null);
      if (byDelivery != null) {
        return byDelivery;
      }
    }
    return claimId == null ? null : store.lastPickup(claimId).orElse(null);
  }

  private void pickupProgress(String type, JsonNode p) {
    String claimId = p.path("claimId").asString(null);
    if (claimId == null) {
      return; // 오배송 회수 등 클레임 없는 회수
    }
    ClaimRow c = store.lock(claimId).orElse(null);
    if (c == null || !"RETURN".equals(c.type())) {
      return;
    }
    String deliveryId = p.path("deliveryId").asString(null);
    PickupRow pickup = pickupOf(claimId, deliveryId);
    Instant now = clock.instant();
    boolean pickedUp = "ReturnPickedUp".equals(type);
    if (pickup != null) {
      if (pickedUp && !"REQUESTED".equals(pickup.state()) && !"FAILED".equals(pickup.state())) {
        return; // 이미 반영한 수거
      }
      if (!pickedUp && "DELIVERED".equals(pickup.state())) {
        return;
      }
      store.updatePickup(
          pickup.pickupRequestId(), pickedUp ? "PICKED_UP" : "DELIVERED", deliveryId, null, now);
    }
    if (pickedUp) {
      Map<String, Integer> room = new LinkedHashMap<>();
      List<LineRow> lines = store.lines(claimId);
      lines.forEach(l -> room.put(l.claimLineId(), l.requestedQty() - l.pickedUpQty()));
      for (JsonNode l : p.path("lines")) {
        Map<String, Integer> got =
            ReturnRules.allocate(
                slots(lines, room),
                l.path("orderLineId").asString(null),
                "",
                l.path("qty").asInt());
        got.forEach(
            (claimLineId, q) -> {
              store.addLineProgress(claimLineId, q, 0, 0, 0);
              room.merge(claimLineId, -q, Integer::sum);
            });
      }
    }
    String next = pickedUp ? "PICKED_UP" : "DELIVERED_TO_STORE";
    if (c.pickupState() == null
        || Set.of("REQUESTED", "PICKED_UP", "FAILED").contains(c.pickupState())) {
      store.updatePickupState(claimId, next, now);
    }
    if (ReturnRules.RECOVERY_FAILED.equals(c.state())) {
      // 실패로 본 뒤 실제로는 받았다(늦은 사건) — 진행으로 되돌린다
      store.updateState(claimId, ReturnRules.IN_PROGRESS, null, null, null, now);
    }
    log.info("반품 {} 회수 {}", claimId, next);
  }

  private void pickupFailed(JsonNode p) {
    String claimId = p.path("claimId").asString(null);
    String deliveryId = p.path("deliveryId").asString(null);
    PickupRow pickup = null;
    if (deliveryId != null) {
      pickup = store.pickupByDelivery(deliveryId).orElse(null);
    }
    if (pickup == null && claimId != null) {
      pickup = store.lastPickup(claimId).orElse(null);
    }
    if (pickup == null) {
      return; // 고객 반품 회수 배송이 아니다(일반 배송 실패)
    }
    ClaimRow c = store.lock(pickup.claimId()).orElse(null);
    if (c == null) {
      return;
    }
    String reason = p.path("reason").asString(null);
    // 고객 사유인가(ReturnPickupFailed.customerReason — DeliveryFailed 에는 없어 사유 코드로)
    Boolean customerReason =
        p.hasNonNull("customerReason") ? p.path("customerReason").asBoolean() : null;
    String kind = ReturnRules.failureKind(reason, customerReason);
    Instant now = clock.instant();
    if (!"REQUESTED".equals(pickup.state())) {
      // 이미 받은 뒤의 실패(반납 전 사고 등)는 회수 실패가 아니다 — 기록만, 사람이 본다
      log.warn(
          "반품 {} 회수 {} 가 {} 인데 배송 실패({}) — 자동 처리 없음",
          c.claimId(),
          pickup.pickupRequestId(),
          pickup.state(),
          reason);
      return;
    }
    store.failPickup(
        pickup.pickupRequestId(), deliveryId, reason == null ? null : trim(reason), kind, now);
    if (!ReturnRules.IN_PROGRESS.equals(c.state())) {
      return;
    }
    int counted = countedFailures(c.claimId()); // 방금 실패 포함(우리 잘못은 빼고)
    if (ReturnRules.pastDeadline(c.returnDeadlineAt(), now)) {
      // 반품 기한(접수 + 7일)이 지난 뒤의 실패 — 더 회수하지 않는다(추가 결정 5)
      if (!anythingCollected(c.claimId())) {
        log.info("반품 {} 기한이 지난 뒤 회수 실패({}) — 자동 취소", c.claimId(), reason);
        cancelReturn(c, "RETURN_DEADLINE", "RETURN_EXPIRED", null, now);
        return;
      }
    } else if (ReturnRules.FAIL_OURS.equals(kind)) {
      // 라이더·우리 잘못 — 횟수에서 빼고 바로 다시(추가 결정 4)
      log.info(
          "반품 {} 회수 {}번째 실패(우리 쪽 {}) — 횟수에 넣지 않고 바로 다시 요청",
          c.claimId(),
          pickup.attemptNo(),
          reason);
      pickups.request(c, "SYSTEM", now);
      return;
    } else if (ReturnRules.autoRetryAfterFailure(counted)) {
      log.info("반품 {} 회수 실패 {}번째({}) — 자동으로 다시 요청", c.claimId(), counted, reason);
      pickups.request(c, "SYSTEM", now);
      return;
    }
    boolean retryAllowed =
        ReturnRules.csRetryAllowed(counted) && !ReturnRules.pastDeadline(c.returnDeadlineAt(), now);
    store.updateState(c.claimId(), ReturnRules.RECOVERY_FAILED, null, null, null, now);
    store.updatePickupState(c.claimId(), "FAILED", now);
    events.returnRecoveryFailed(
        c, pickup.attemptNo(), counted, retryAllowed, deliveryId, reason, now);
    log.info(
        "반품 {} 회수 실패 {}번째({}) — 상담원 재연락(다시 회수 {})",
        c.claimId(),
        counted,
        reason,
        retryAllowed ? "가능" : "불가 — 취소만");
  }

  // ---------- 반품 기한(추가 결정 5) ----------

  /**
   * 반품 전체 기한(접수 + {@code pmart.claim.return-deadline})이 지났는데 회수가 하나도 안 된 반품을 자동 취소한다. 진행 중인 회수 요청이
   * 있으면 그 결과를 기다리고(실패하면 그때 취소 — {@link #onDeliveryEvent}), 무엇이든 수거·반입됐으면 상담원 "받은 만큼만 끝내기"로 둔다. 낸
   * 회수비는 회수 실패 종류 규칙대로(파손·기타는 상담원 판단이 없으니 돌려주지 않음).
   */
  @Scheduled(fixedDelayString = "${pmart.claim.return-deadline-sweep-interval:PT5M}")
  public void expireOverdue() {
    for (String claimId : store.overdueReturns(clock.instant())) {
      try {
        expire(claimId);
      } catch (RuntimeException ex) {
        log.warn("반품 {} 기한 취소 실패 — 다음에 다시: {}", claimId, ex.toString());
      }
    }
  }

  /** 한 반품의 기한 판정(잠금 아래에서 다시 확인). */
  public void expire(String claimId) {
    tx.executeWithoutResult(
        s -> {
          ClaimRow c = store.lock(claimId).orElse(null);
          if (c == null
              || !"RETURN".equals(c.type())
              || !Set.of(
                      ReturnRules.FEE_PENDING, ReturnRules.IN_PROGRESS, ReturnRules.RECOVERY_FAILED)
                  .contains(c.state())) {
            return;
          }
          Instant now = clock.instant();
          if (!ReturnRules.pastDeadline(c.returnDeadlineAt(), now)
              || anythingCollected(claimId)
              || store.pickups(claimId).stream().anyMatch(x -> "REQUESTED".equals(x.state()))) {
            return;
          }
          log.info("반품 {} 기한({})까지 회수 안 됨 — 자동 취소", claimId, c.returnDeadlineAt());
          cancelReturn(c, "RETURN_DEADLINE", "RETURN_EXPIRED", null, now);
        });
  }

  /**
   * 반품 취소(상담원 CANCEL·기한 자동 취소, 호출자 트랜잭션 안). 결제 대기 회수비는 더 받지 않고(그 뒤 승인되면 보상 취소), 낸 회수비는 회수 실패 종류
   * 규칙(추가 결정 2) — 돌려주면 회수비 결제의 전액 취소 의도를 만들고 커밋 뒤 실행.
   *
   * @param csFeeRefund 상담원 선택(파손·기타일 때만 쓰임). 규칙과 다르면 {@link ClaimRules.Rejection} — 아무것도 바꾸기 전에
   */
  private void cancelReturn(
      ClaimRow c, String rejectReason, String outcome, @Nullable Boolean csFeeRefund, Instant now) {
    FeeRow f = store.lockFee(c.claimId()).orElse(null);
    boolean paid = f != null && ReturnRules.FEE_PAID.equals(f.state());
    boolean refundFee = false;
    if (paid) {
      refundFee =
          ReturnRules.refundFee(ReturnRules.feeRefundRule(failureKinds(c.claimId())), csFeeRefund);
    } else if (Boolean.TRUE.equals(csFeeRefund)) {
      throw new ClaimRules.Rejection("FEE_REFUND_NOT_ALLOWED", "돌려줄 회수비가 없습니다(면제이거나 아직 결제되지 않음).");
    }
    fees.stopWaiting(c.claimId(), now);
    if (paid) {
      if (refundFee) {
        RefundRow r = refunds.createFeeRefund(c, f, now);
        store.decideFeeRefund(c.claimId(), ReturnRules.FEE_REFUND, r.refundIntentId(), now);
        String refundId = r.refundIntentId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                refunds.executeAsync(refundId);
              }
            });
        log.info("반품 {} 취소 — 낸 회수비 {}원 돌려줌({})", c.claimId(), f.amount(), refundId);
      } else {
        store.decideFeeRefund(c.claimId(), ReturnRules.FEE_KEEP, null, now);
        log.info("반품 {} 취소 — 낸 회수비는 돌려주지 않음", c.claimId());
      }
    }
    store.updateState(c.claimId(), ReturnRules.CANCELLED, rejectReason, null, now, now);
    events.claimClosed(c, outcome, now);
  }

  // ---------- 받은 만큼만 끝내기(추가 결정 6) ----------

  /**
   * MFC 가 요청보다 적게 받았을 때 상담원이 끝낸다(호출자 트랜잭션 안, 클레임 잠금 뒤): 행마다 정산 수량 = 받은 수량, 남은 수량은 환불 없음. 받은 수량이 모두
   * 검수·환불되면 CLOSED(RETURN_SHORT_CLOSED), 아직이면 IN_PROGRESS 로 두고 검수·환불 뒤 끝난다. 진행 중인 회수 요청이 있으면 409.
   */
  private void closeAsReceived(ClaimRow c, Instant now) {
    if (!ReturnRules.IN_PROGRESS.equals(c.state())
        && !ReturnRules.RECOVERY_FAILED.equals(c.state())) {
      throw PmartException.invalidState("회수 진행·회수 실패 상태에서만 받은 만큼만 끝냅니다(지금 " + c.state() + ").");
    }
    if (store.pickups(c.claimId()).stream().anyMatch(x -> "REQUESTED".equals(x.state()))) {
      throw conflict("PICKUP_IN_PROGRESS", "진행 중인 회수 요청이 있습니다 — 결과(수거·실패)를 받은 뒤 끝내세요.");
    }
    List<LineRow> lines = store.lines(c.claimId());
    Map<String, Integer> settle;
    try {
      settle =
          ReturnRules.shortClose(
              lines.stream()
                  .map(
                      l ->
                          new ReturnRules.ShortLine(
                              l.claimLineId(), l.requestedQty(), l.receivedQty(), l.inspectedQty()))
                  .toList());
    } catch (ClaimRules.Rejection r) {
      throw conflict(r.code(), r.getMessage());
    }
    settle.forEach(store::settleLine);
    // 회수 실패에서 끝내도 검수·환불을 받도록 진행으로(버전 +1 — 상담원 조치)
    store.updateState(c.claimId(), ReturnRules.IN_PROGRESS, null, null, null, now);
    boolean allInspected =
        lines.stream().allMatch(l -> l.inspectedQty() >= settle.get(l.claimLineId()));
    if (allInspected) {
      store.updatePickupState(c.claimId(), "INSPECTED", now);
    }
    if (!refunds.closeReturnIfDone(c.claimId(), now)) {
      log.info("반품 {} 받은 만큼만 끝내기 — 남은 검수·환불 뒤 종결", c.claimId());
    }
  }

  private List<String> failureKinds(String claimId) {
    return store.pickups(claimId).stream()
        .filter(PickupRow::failed)
        .map(x -> x.failureKind() == null ? ReturnRules.FAIL_OTHER : x.failureKind())
        .toList();
  }

  private int countedFailures(String claimId) {
    return ReturnRules.countedFailures(failureKinds(claimId));
  }

  /** 무엇이든 수거·반입·검수됐나(회수가 일부라도 된 반품 — 자동 취소·상담원 취소 대상이 아니다). */
  private boolean anythingCollected(String claimId) {
    return store.lines(claimId).stream()
        .anyMatch(l -> l.pickedUpQty() > 0 || l.receivedQty() > 0 || l.inspectedQty() > 0);
  }

  private static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }

  /** mfc.inbound.v1 — ReturnItemReceived: 수령 수량(검수 대기). 환불은 검수 뒤. */
  public void onInboundEvent(EventEnvelope e) {
    if (!"ReturnItemReceived".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String claimId = p.path("claimId").asString(null);
    if (claimId == null) {
      return;
    }
    ClaimRow c = store.lock(claimId).orElse(null);
    if (c == null || !"RETURN".equals(c.type())) {
      return;
    }
    Instant now = clock.instant();
    String receiptId = p.path("returnReceiptId").asString(e.eventId().toString());
    if (!store.markFact("RECEIPT", receiptId, claimId, now)) {
      return;
    }
    List<LineRow> lines = store.lines(claimId);
    Map<String, Integer> room = new LinkedHashMap<>();
    lines.forEach(l -> room.put(l.claimLineId(), l.requestedQty() - l.receivedQty()));
    for (JsonNode l : p.path("lines")) {
      int qty = l.path("qty").asInt();
      Map<String, Integer> got =
          ReturnRules.allocate(
              slots(lines, room),
              l.path("orderLineId").asString(null),
              l.path("sku").asString(""),
              qty);
      int taken = got.values().stream().mapToInt(Integer::intValue).sum();
      if (taken < qty) {
        log.warn("반품 {} 반입 {} — 요청보다 {}개 더 들어옴(기록하지 않음)", claimId, receiptId, qty - taken);
      }
      got.forEach(
          (claimLineId, q) -> {
            store.addLineProgress(claimLineId, 0, q, 0, 0);
            room.merge(claimLineId, -q, Integer::sum);
          });
    }
    if (!"INSPECTED".equals(c.pickupState())) {
      store.updatePickupState(claimId, "RECEIVED", now);
    }
    if (ReturnRules.RECOVERY_FAILED.equals(c.state())) {
      store.updateState(claimId, ReturnRules.IN_PROGRESS, null, null, null, now);
    } else if (!ReturnRules.IN_PROGRESS.equals(c.state())) {
      log.warn("반품 {} 가 {} 인데 회수품이 MFC 에 들어옴({}) — 수량만 기록", claimId, c.state(), receiptId);
    }
    log.info("반품 {} MFC 반입 {}", claimId, receiptId);
  }

  /** mfc.quality.v1 — ReturnInspected: 검수 수량 원단가 환불(RESALE·DISPOSE 모두, 1차 감액 없음). */
  public void onQualityEvent(EventEnvelope e) {
    if (!"ReturnInspected".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String claimId = p.path("claimId").asString(null);
    if (claimId == null) {
      return;
    }
    ClaimRow c = store.lock(claimId).orElse(null);
    if (c == null || !"RETURN".equals(c.type())) {
      return;
    }
    String inspectionId = p.path("inspectionId").asString(e.eventId().toString());
    if (!ReturnRules.IN_PROGRESS.equals(c.state())
        && !ReturnRules.RECOVERY_FAILED.equals(c.state())) {
      log.error(
          "반품 {} 가 {} 인데 검수 {} 가 들어옴 — 자동 환불하지 않는다(운영자 확인)", claimId, c.state(), inspectionId);
      return;
    }
    Instant now = clock.instant();
    if (!store.markFact("INSPECTION", inspectionId, claimId, now)) {
      return;
    }
    if (ReturnRules.RECOVERY_FAILED.equals(c.state())) {
      store.updateState(claimId, ReturnRules.IN_PROGRESS, null, null, null, now);
      c = store.lock(claimId).orElseThrow();
    }
    List<LineRow> lines = store.lines(claimId);
    Map<String, LineRow> byId = new HashMap<>();
    lines.forEach(l -> byId.put(l.claimLineId(), l));
    Map<String, Integer> room = new LinkedHashMap<>();
    Map<String, Integer> inspected = new HashMap<>();
    // 남은 칸 = 정산 수량(접수 = 요청 수량, 받은 만큼만 끝내기 뒤 = 받은 수량) − 검수됨. 넘는 검수는 환불하지 않는다
    lines.forEach(
        l -> {
          room.put(l.claimLineId(), RefundService.settleQty(l) - l.inspectedQty());
          inspected.put(l.claimLineId(), l.inspectedQty());
        });
    long items = 0;
    long refund = 0;
    List<Map<String, Object>> basis = new ArrayList<>();
    for (JsonNode l : p.path("lines")) {
      int qty = l.path("qty").asInt();
      boolean resale = "RESALE".equals(l.path("disposition").asString());
      Map<String, Integer> got =
          ReturnRules.allocate(
              slots(lines, room),
              l.path("orderLineId").asString(null),
              l.path("sku").asString(""),
              qty);
      int taken = got.values().stream().mapToInt(Integer::intValue).sum();
      if (taken < qty) {
        log.warn("반품 {} 검수 {} — 요청보다 {}개 더 검수됨(환불하지 않음)", claimId, inspectionId, qty - taken);
      }
      for (Map.Entry<String, Integer> g : got.entrySet()) {
        LineRow line = byId.get(g.getKey());
        int q = g.getValue();
        long unit = line.unitPrice() == null ? 0 : line.unitPrice();
        int original = line.originalQty() == null ? 0 : line.originalQty();
        long amount =
            ReturnRules.refundAmount(
                unit, original, line.discountShare(), inspected.get(g.getKey()), q);
        items += unit * q;
        refund += amount;
        inspected.merge(g.getKey(), q, Integer::sum);
        room.merge(g.getKey(), -q, Integer::sum);
        store.addLineProgress(g.getKey(), 0, 0, resale ? q : 0, resale ? 0 : q);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("orderLineId", line.orderLineId());
        b.put("qty", q);
        b.put("amount", amount);
        b.put("disposition", resale ? "RESALE" : "DISPOSE");
        basis.add(b);
      }
    }
    boolean all = room.values().stream().allMatch(v -> v <= 0);
    store.updatePickupState(claimId, all ? "INSPECTED" : "RECEIVED", now);
    if (refund > 0) {
      RefundRow r = refunds.createReturnRefund(c, inspectionId, items, items - refund, basis, now);
      String refundId = r.refundIntentId();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              refunds.executeAsync(refundId);
            }
          });
      log.info("반품 {} 검수 {} — 환불 {} {}원", claimId, inspectionId, refundId, refund);
    }
    refunds.closeReturnIfDone(claimId, now);
  }

  // ---------- 공통 ----------

  private static List<ReturnRules.ClaimLineSlot> slots(
      List<LineRow> lines, Map<String, Integer> room) {
    List<ReturnRules.ClaimLineSlot> out = new ArrayList<>();
    for (LineRow l : lines) {
      out.add(
          new ReturnRules.ClaimLineSlot(
              l.claimLineId(), l.orderLineId(), l.sku(), room.getOrDefault(l.claimLineId(), 0)));
    }
    return out;
  }

  private static void requireStaff(PmartPrincipal who) {
    if (who.type() != PmartPrincipal.PrincipalType.OPERATOR
        || ReturnRules.STAFF_ROLES.stream().noneMatch(who::hasRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "반품(회수) 접수·조치는 고객센터 상담원·운영 관리자만 할 수 있습니다.");
    }
  }

  private static String trim(String s) {
    return s.length() <= 64 ? s : s.substring(0, 64);
  }
}
