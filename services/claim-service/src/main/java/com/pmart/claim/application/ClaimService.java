package com.pmart.claim.application;

import com.pmart.claim.domain.ClaimRules;
import com.pmart.claim.domain.ReturnRules;
import com.pmart.claim.infrastructure.ClaimStore;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.LineRow;
import com.pmart.claim.infrastructure.ClaimStore.RefundRow;
import com.pmart.claim.infrastructure.Upstreams;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * 클레임 접수·조회와 취소 흐름의 사건 반영(docs/business/flows/cancel-vs-handover.md). 2026-10-31 범위: 고객의 주문 전체
 * 취소(피킹 전).
 *
 * <ol>
 *   <li>접수: order 원장으로 본인·상태·수량 심사({@link ClaimRules}) → 클레임 STOP_PENDING + stopRequestId 발급 →
 *       ClaimOpened· CancellationRequested(order.claim.v1, 한 트랜잭션). orchestration 이 그 사건으로 S3 를
 *       시작한다.
 *   <li>WorkStopResult 에 인계됨이 있으면 REJECTED(출고 전 취소 불가 — 회수 경로).
 *   <li>OrderCancelled(claimId) → 행 승인·IN_PROGRESS → 환불 의도(원 승인액 전액) → 커밋 뒤 payment 취소 실행({@link
 *       RefundService}).
 *   <li>RefundResult SUCCESS → 환불 완료·클레임 CLOSED. PG 성공 전에는 완료로 표시하지 않는다.
 * </ol>
 *
 * 고객센터 직원의 접수는 인계 뒤 회수 반품(RETURN)만 — {@link ReturnService}(CL-1, 2026-11-17).
 */
@Service
public class ClaimService {

  public static final String TOPIC = "order.claim.v1";

  private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

  /** 접수 명령(계약 CreateClaimRequest). */
  public record CreateCommand(
      String orderId,
      String type,
      List<ClaimRules.Requested> lines,
      String reason,
      @Nullable String reasonDetail,
      String channel) {}

  /**
   * @param fee 반품 회수비(RETURN 고객 사유만, 없으면 null)
   * @param pickups 반품 회수 요청(시도 순)
   */
  public record ClaimView(
      ClaimRow claim,
      List<LineRow> lines,
      List<RefundRow> refunds,
      ClaimStore.@Nullable FeeRow fee,
      List<ClaimStore.PickupRow> pickups,
      int claimSeq,
      java.util.Map<String, Integer> refundSeqs) {

    static ClaimView of(ClaimStore store, String claimId) {
      return of(store, store.find(claimId).orElseThrow());
    }

    static ClaimView of(ClaimStore store, ClaimRow c) {
      boolean ret = "RETURN".equals(c.type());
      return new ClaimView(
          c,
          store.lines(c.claimId()),
          store.refunds(c.claimId()),
          ret ? store.fee(c.claimId()).orElse(null) : null,
          ret ? store.pickups(c.claimId()) : List.of(),
          store.claimSeqs(List.of(c.claimId())).getOrDefault(c.claimId(), 0),
          store.refundSeqs(c.claimId()));
    }
  }

  /** 시스템 자동 취소 결과. created=false 면 이미 진행 중이던 취소 클레임. */
  public record SystemCancellation(ClaimView view, boolean created) {}

  private record Opened(String claimId, boolean created) {}

  public static final String SYSTEM_CHANNEL = "SYSTEM";

  private final ClaimStore store;
  private final Upstreams upstreams;
  private final RequestIdempotency idempotency;
  private final ClaimEvents events;
  private final RefundService refunds;
  private final ReturnService returns;
  private final Clock clock;

  public ClaimService(
      ClaimStore store,
      Upstreams upstreams,
      RequestIdempotency idempotency,
      ClaimEvents events,
      RefundService refunds,
      ReturnService returns,
      Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.idempotency = idempotency;
    this.events = events;
    this.refunds = refunds;
    this.returns = returns;
    this.clock = clock;
  }

  // ---------- 접수 ----------

  /** 반품 접수 추가 입력(직원 RETURN — CL-1). */
  public record ReturnExtras(
      @Nullable String faultCode,
      @Nullable String identityMethod,
      @Nullable String identityNote,
      @Nullable String caseRef) {}

  /**
   * 고객 앱: 주문 전체 취소(CANCEL). 고객센터 직원: 인계 뒤 회수(RETURN — {@link ReturnService}, CL-1·D-7). 상품준비 뒤 고객센터
   * 취소는 없다(ADR-0060), 고객 앱 반품은 고객센터로 안내.
   */
  public ClaimView create(
      String idempotencyKey,
      String sourceSystem,
      PmartPrincipal who,
      CreateCommand in,
      ReturnExtras extras) {
    // 0.11.0 DC-1: 회사 사유 전체 취소만 lines 생략 가능(서버가 채움). 그 밖은 필요
    boolean companyCancel =
        who.type() == PmartPrincipal.PrincipalType.OPERATOR
            && "CANCEL".equals(in.type())
            && ReturnRules.COMPANY_CANCEL_REASONS.contains(in.reason());
    if (!companyCancel && in.lines().isEmpty()) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "취소·반품할 행(lines)이 필요합니다.");
    }
    if (who.type() == PmartPrincipal.PrincipalType.OPERATOR) {
      if ("CANCEL".equals(in.type())) {
        return createStaffCancel(idempotencyKey, sourceSystem, who, in, extras);
      }
      if (!"RETURN".equals(in.type())) {
        throw unsupported("고객센터는 상품준비 전 주문 취소(CANCEL)와 인계 뒤 회수(RETURN)만 접수합니다.");
      }
      return returns.create(
          idempotencyKey,
          sourceSystem,
          who,
          new ReturnService.CreateReturn(
              in.orderId(),
              in.lines(),
              in.reason(),
              in.reasonDetail(),
              in.channel(),
              extras.faultCode(),
              extras.identityMethod(),
              extras.identityNote(),
              extras.caseRef()));
    }
    if (who.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw unsupported("고객 앱의 취소 접수와 고객센터의 반품 접수만 받습니다.");
    }
    if ("RETURN".equals(in.type())) {
      throw unsupported("받으신 뒤 반품·회수는 고객센터로 문의해 주세요.");
    }
    if (!"CANCEL".equals(in.type())) {
      throw unsupported("교환 접수는 아직 준비 중입니다.");
    }
    if ("FULFILLMENT_REJECTED".equals(in.reason())
        || OUT_OF_STOCK.equals(in.reason())
        || ReturnRules.COMPANY_CANCEL_REASONS.contains(in.reason())) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "시스템·직원 전용 사유입니다: " + in.reason());
    }
    JsonNode order = upstreams.order(in.orderId());
    if (order == null || !who.subject().equals(order.path("accountId").asString())) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    List<ClaimRules.OrderLine> lines = new ArrayList<>();
    Map<String, JsonNode> byLine = new LinkedHashMap<>();
    for (JsonNode l : order.path("lines")) {
      lines.add(
          new ClaimRules.OrderLine(
              l.path("orderLineId").asString(),
              l.path("sku").asString(),
              l.path("unitPrice").asLong(),
              l.path("originalQty").asInt(),
              l.path("unfulfilledQty").asInt()));
      byLine.put(l.path("orderLineId").asString(), l);
    }
    try {
      ClaimRules.checkFullCancel(order.path("status").asString(), lines, in.lines());
    } catch (ClaimRules.Rejection r) {
      throw new PmartException(
          r.code(), HttpStatus.UNPROCESSABLE_CONTENT, r.getMessage(), false, List.of());
    }
    String claimId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createClaim", who.subject()),
                in,
                String.class,
                () -> open(who.subject(), in, order, byLine))
            .value();
    return view(claimId);
  }

  /**
   * 고객센터 주문 전체 취소(2026-11-18, 사용자 "그 추천대로" — 저녁 목록 3번). 고객 앱 취소와 같은 범위·흐름: 주문 {@code
   * CONFIRMED}(상품준비 = 첫 피킹 시작 전 — 작업자 배정만 된 주문 포함)·모든 행 × 미이행 수량. 상품준비 뒤는 ADR-0060 그대로 422
   * ORDER_NOT_CANCELLABLE. 고객센터 상담원·운영 관리자만(403), 본인 확인 방법 필수(OTHER 면 note — 반품과 같은 규칙), 사유 필수(시스템
   * 사유 400), 채널 CS·OPERATOR. 접수 직원·본인 확인 방법·메모·CS 사건은 클레임에 남는다(created_by·identity_*·case_ref). 뒤
   * 흐름(현장 중지 → OrderCancelled → 전액 환불)은 고객 취소와 같다.
   */
  private ClaimView createStaffCancel(
      String idempotencyKey,
      String sourceSystem,
      PmartPrincipal who,
      CreateCommand in,
      ReturnExtras extras) {
    if (ReturnRules.COMPANY_CANCEL_REASONS.contains(in.reason())) {
      return createCompanyReasonCancel(idempotencyKey, sourceSystem, who, in, extras);
    }
    if (ReturnRules.STAFF_ROLES.stream().noneMatch(who::hasRole)) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.FORBIDDEN, "주문 취소 접수는 고객센터 상담원·운영 관리자만 할 수 있습니다.");
    }
    String identity = ReturnRules.identityRejection(extras.identityMethod(), extras.identityNote());
    if (identity != null) {
      throw new PmartException(com.pmart.common.web.ErrorCode.VALIDATION_FAILED, identity);
    }
    if (!ReturnRules.STAFF_CHANNELS.contains(in.channel())) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED,
          "고객센터 취소는 채널 CS·OPERATOR 로 접수합니다: " + in.channel());
    }
    if ("FULFILLMENT_REJECTED".equals(in.reason()) || OUT_OF_STOCK.equals(in.reason())) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "시스템 전용 사유입니다: " + in.reason());
    }
    if (extras.faultCode() != null) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "회수비 면제 사유(faultCode)는 반품 접수에만 씁니다.");
    }
    JsonNode order = upstreams.order(in.orderId());
    if (order == null) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    List<ClaimRules.OrderLine> lines = new ArrayList<>();
    Map<String, JsonNode> byLine = new LinkedHashMap<>();
    for (JsonNode l : order.path("lines")) {
      lines.add(
          new ClaimRules.OrderLine(
              l.path("orderLineId").asString(),
              l.path("sku").asString(),
              l.path("unitPrice").asLong(),
              l.path("originalQty").asInt(),
              l.path("unfulfilledQty").asInt()));
      byLine.put(l.path("orderLineId").asString(), l);
    }
    try {
      ClaimRules.checkFullCancel(order.path("status").asString(), lines, in.lines());
    } catch (ClaimRules.Rejection r) {
      String m =
          "ORDER_NOT_CANCELLABLE".equals(r.code())
              ? "상품 준비가 시작된 주문은 취소할 수 없습니다(ADR-0060) — 받으신 뒤 반품으로 안내해 주세요. (" + r.getMessage() + ")"
              : r.getMessage();
      throw new PmartException(r.code(), HttpStatus.UNPROCESSABLE_CONTENT, m, false, List.of());
    }
    String accountId = order.path("accountId").asString();
    String claimId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createStaffCancel", who.subject()),
                java.util.Arrays.asList(in, extras),
                String.class,
                () -> open(accountId, in, order, byLine, who.subject(), extras))
            .value();
    log.info("고객센터 취소 접수 {} 주문 {} 직원 {}", claimId, in.orderId(), who.subject());
    return view(claimId);
  }

  /**
   * 배차 불가 취소(CL-5, 2026-11-19 — 원문 Delivery §3.6 "배차 불가로 취소 시 환불 및 쿠폰 보상", 쿠폰은 promotion 이 생길 때).
   * 회사 사유라 본인 확인 없이, 상품준비 뒤라도 인계 전이면 시스템 취소와 같은 범위({@code checkFullCancel(…, system=true)} — 인계
   * 뒤·미이행 0 은 422). 그 센터 MFC_SUPERVISOR(그 센터 권한)·CS_AGENT·OPERATOR_ADMIN. 뒤 흐름은 고객센터 취소와 같다(S3 →
   * OrderCancelled → 원 승인액 전액 환불, dispatch 는 주문 취소 사건으로 픽업 전 배송을 거둔다).
   *
   * <p>관리자 판단 취소(사유 {@code OPERATOR_DECISION}, 2026-11-20 ADR-0076 — 사용자 "관리자 사유 취소만 넓힘", MFC 시뮬레이터
   * R-29)도 같은 범위·흐름이고, 사유 메모(reasonDetail)가 필수다(빈 값 400). 역할은 그 센터 MFC_SUPERVISOR·OPERATOR_ADMIN
   * 만(CS_AGENT 403). 고객 취소 범위(상품준비 전만)는 그대로.
   */
  private ClaimView createCompanyReasonCancel(
      String idempotencyKey,
      String sourceSystem,
      PmartPrincipal who,
      CreateCommand in,
      ReturnExtras extras) {
    boolean decision = ReturnRules.OPERATOR_DECISION.equals(in.reason());
    String label = decision ? "관리자 판단 취소" : "배차 불가 취소";
    // 센터와 무관하게 할 수 있는 역할 — 관리자 판단은 운영 관리자만(CS_AGENT 제외, 사용자 "관리자 사유 취소만 넓힘"),
    // 배차 불가는 고객센터 상담원·운영 관리자(그대로)
    boolean staff =
        decision
            ? who.hasRole(ReturnRules.OPERATOR_ADMIN)
            : ReturnRules.STAFF_ROLES.stream().anyMatch(who::hasRole);
    if (!staff && !who.hasRole(ReturnRules.MFC_SUPERVISOR)) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.FORBIDDEN,
          decision
              ? label + "는 센터 관리자·운영 관리자만 할 수 있습니다."
              : label + "는 센터 관리자·고객센터 상담원·운영 관리자만 할 수 있습니다.");
    }
    if (decision && (in.reasonDetail() == null || in.reasonDetail().isBlank())) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED,
          "관리자 판단 취소는 사유 메모(reasonDetail)가 필요합니다.");
    }
    if (!ReturnRules.STAFF_CHANNELS.contains(in.channel())) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED,
          label + "는 채널 CS·OPERATOR 로 접수합니다: " + in.channel());
    }
    if (extras.faultCode() != null) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "회수비 면제 사유(faultCode)는 반품 접수에만 씁니다.");
    }
    JsonNode order = upstreams.order(in.orderId());
    if (order == null) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    if (!staff && !who.canAccessCenter(order.path("mfcId").asString(""))) {
      // 센터 관리자는 자기 센터 주문만 — 남의 센터 주문은 있는지도 알리지 않는다
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    List<ClaimRules.OrderLine> lines = new ArrayList<>();
    Map<String, JsonNode> byLine = new LinkedHashMap<>();
    for (JsonNode l : order.path("lines")) {
      lines.add(
          new ClaimRules.OrderLine(
              l.path("orderLineId").asString(),
              l.path("sku").asString(),
              l.path("unitPrice").asLong(),
              l.path("originalQty").asInt(),
              l.path("unfulfilledQty").asInt()));
      byLine.put(l.path("orderLineId").asString(), l);
    }
    // 0.11.0 DC-1: lines 를 생략했으면 모든 행 × 미이행 수량(시스템 취소와 같은 채우기). 멱등 해시는 받은 본문 그대로
    CreateCommand filled =
        in.lines().isEmpty()
            ? new CreateCommand(
                in.orderId(),
                in.type(),
                lines.stream()
                    .filter(l -> l.unfulfilledQty() > 0)
                    .map(l -> new ClaimRules.Requested(l.orderLineId(), l.unfulfilledQty()))
                    .toList(),
                in.reason(),
                in.reasonDetail(),
                in.channel())
            : in;
    try {
      ClaimRules.checkFullCancel(order.path("status").asString(), lines, filled.lines(), true);
    } catch (ClaimRules.Rejection r) {
      String m =
          "ORDER_NOT_CANCELLABLE".equals(r.code())
              ? "이미 라이더에게 넘긴 주문은 " + label + "로 처리할 수 없습니다 — 받으신 뒤 반품으로. (" + r.getMessage() + ")"
              : r.getMessage();
      throw new PmartException(r.code(), HttpStatus.UNPROCESSABLE_CONTENT, m, false, List.of());
    }
    String accountId = order.path("accountId").asString();
    String claimId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createStaffCancel", who.subject()),
                java.util.Arrays.asList(in, extras),
                String.class,
                () -> open(accountId, filled, order, byLine, who.subject(), extras))
            .value();
    log.info("{} 접수 {} 주문 {} 직원 {}", label, claimId, in.orderId(), who.subject());
    return view(claimId);
  }

  /**
   * 시스템 자동 전체 취소(D6-4, 슬라이스 6a). orchestration 이 MFC 작업 거절을 확인한 뒤 부른다. 고객 선택 단계 없이 주문 전체(모든 행 × 미이행
   * 수량)를 취소 접수 — 뒤 흐름(S3 → OrderCancelled → 원 승인액 전액 환불)은 고객 취소와 같다. 이미 진행 중인 취소 클레임이 있으면 새로 만들지 않고
   * 그것을 돌려준다(고객이 먼저 취소한 경우).
   */
  public SystemCancellation createSystemCancellation(
      String idempotencyKey,
      String sourceSystem,
      String orderId,
      String reason,
      @Nullable String reasonDetail) {
    JsonNode order = upstreams.order(orderId);
    if (order == null) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    List<ClaimRules.OrderLine> lines = new ArrayList<>();
    List<ClaimRules.Requested> all = new ArrayList<>();
    Map<String, JsonNode> byLine = new LinkedHashMap<>();
    for (JsonNode l : order.path("lines")) {
      String id = l.path("orderLineId").asString();
      int unfulfilled = l.path("unfulfilledQty").asInt();
      lines.add(
          new ClaimRules.OrderLine(
              id,
              l.path("sku").asString(),
              l.path("unitPrice").asLong(),
              l.path("originalQty").asInt(),
              unfulfilled));
      if (unfulfilled > 0) {
        all.add(new ClaimRules.Requested(id, unfulfilled));
      }
      byLine.put(id, l);
    }
    CreateCommand in =
        new CreateCommand(orderId, "CANCEL", all, reason, reasonDetail, SYSTEM_CHANNEL);
    Opened o =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createSystemCancellation", actor()),
                in,
                Opened.class,
                () -> {
                  ClaimRow active = store.activeCancel(orderId).orElse(null);
                  if (active != null) {
                    log.info(
                        "주문 {} 에 진행 중인 취소 {} 가 있어 자동 취소를 새로 만들지 않는다", orderId, active.claimId());
                    return new Opened(active.claimId(), false);
                  }
                  if (all.isEmpty()) {
                    throw new PmartException(
                        "ORDER_NOT_CANCELLABLE",
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "취소할 미이행 수량이 없습니다.",
                        false,
                        List.of());
                  }
                  try {
                    ClaimRules.checkFullCancel(order.path("status").asString(), lines, all, true);
                  } catch (ClaimRules.Rejection r) {
                    throw new PmartException(
                        r.code(),
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        r.getMessage(),
                        false,
                        List.of());
                  }
                  return new Opened(
                      open(order.path("accountId").asString(), in, order, byLine), true);
                })
            .value();
    return new SystemCancellation(view(o.claimId()), o.created());
  }

  private String open(
      String accountId, CreateCommand in, JsonNode order, Map<String, JsonNode> byLine) {
    return open(accountId, in, order, byLine, null, null);
  }

  /** {@code staffId}·{@code staff} 있으면 고객센터 접수 — 접수 직원·본인 확인을 클레임에 남긴다. */
  private String open(
      String accountId,
      CreateCommand in,
      JsonNode order,
      Map<String, JsonNode> byLine,
      @Nullable String staffId,
      @Nullable ReturnExtras staff) {
    if (store.activeCancel(in.orderId()).isPresent()) {
      throw new PmartException(
          "DUPLICATE_CLAIM",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "이미 진행 중인 취소 요청이 있습니다.",
          false,
          List.of());
    }
    Instant now = clock.instant();
    String claimId = "CLM-" + UUID.randomUUID();
    String stopRequestId = "STOP-" + UUID.randomUUID();
    ClaimRow c =
        new ClaimRow(
            claimId,
            in.orderId(),
            accountId,
            in.type(),
            "STOP_PENDING",
            in.reason(),
            in.reasonDetail(),
            in.channel(),
            stopRequestId,
            1,
            order.path("paymentAttemptId").asString(),
            order.path("approvedAmount").asLong(),
            // ADR-0082: 상품 환불액은 쿠폰 할인을 뺀 값(결제액 = 상품액 − 쿠폰 + 배달팁 + 봉투값)
            order.path("itemAmount").asLong() - order.path("couponDiscount").asLong(0),
            order.path("bagFee").asLong(),
            order.path("deliveryTip").asLong(),
            null,
            now,
            null,
            null,
            now,
            null,
            staff == null ? null : staff.identityMethod(),
            staff == null ? null : staff.identityNote(),
            staff == null ? null : staff.caseRef(),
            staffId,
            order.path("mfcId").asString(null), // 0.13.0 CL-8 원주문 이행 센터(취소도 저장)
            null,
            null);
    List<LineRow> rows = new ArrayList<>();
    int n = 1;
    for (ClaimRules.Requested r : in.lines()) {
      JsonNode l = byLine.get(r.orderLineId());
      long unitPrice = l == null ? 0 : l.path("unitPrice").asLong();
      rows.add(
          new LineRow(
              claimId + "-L" + n++,
              claimId,
              r.orderLineId(),
              l == null ? "" : l.path("sku").asString(),
              r.qty(),
              null,
              "PENDING",
              unitPrice * r.qty()));
    }
    store.insert(c, rows);
    events.claimOpened(c, rows);
    events.cancellationRequested(c, rows);
    log.info("취소 접수 {} 주문 {} 중지 요청 {}", claimId, in.orderId(), stopRequestId);
    return claimId;
  }

  // ---------- 결품 부분 환불(ADR-0045) ----------

  public static final String OUT_OF_STOCK = "OUT_OF_STOCK";

  /** 결품 부분 환불 요청 행. */
  /**
   * @param feeDeduction 공제액(회수비 등 — ADR-0065 고객 잘못 오배송 9,000원). 환불 = 상품값 − 공제(0 이상)
   */
  public record ShortageRefund(
      String orderId,
      List<ClaimRules.Requested> lines,
      @Nullable String reasonDetail,
      @Nullable String sourceRef,
      long feeDeduction) {}

  /**
   * 품절 상품만 취소(CANCEL_OOS_ONLY) 의 부분 환불. 주문 수량 변경(SHORTAGE) 뒤에 부른다 — 요청 수량 ≤ 출고 전 취소 누계 − 이미 결품 환불한
   * 수량. 환불액 = 단가 × 수량(할인 안분 1차 제외). 클레임은 바로 IN_PROGRESS, 환불 의도 PARTIAL → 커밋 뒤 payment 부분 취소.
   */
  public ClaimView createShortageRefund(
      String idempotencyKey, String sourceSystem, ShortageRefund in) {
    JsonNode order = upstreams.order(in.orderId());
    if (order == null) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    Map<String, JsonNode> byLine = new LinkedHashMap<>();
    for (JsonNode l : order.path("lines")) {
      byLine.put(l.path("orderLineId").asString(), l);
    }
    String claimId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createShortageRefund", actor()),
                in,
                String.class,
                () -> {
                  long amount = 0;
                  for (ClaimRules.Requested r : in.lines()) {
                    JsonNode l = byLine.get(r.orderLineId());
                    if (l == null) {
                      throw unprocessable(
                          "QUANTITY_EXCEEDS_AVAILABLE", "주문에 없는 행입니다: " + r.orderLineId());
                    }
                    int cancelled = l.path("cancelledBeforeShipQty").asInt(0);
                    int refunded = store.shortageRefundedQty(in.orderId(), r.orderLineId());
                    if (r.qty() > cancelled - refunded) {
                      throw unprocessable(
                          "QUANTITY_EXCEEDS_AVAILABLE",
                          "행 "
                              + r.orderLineId()
                              + ": 요청 "
                              + r.qty()
                              + " > 출고 전 취소 "
                              + cancelled
                              + " − 이미 결품 환불 "
                              + refunded);
                    }
                    // ADR-0082: 할인(기간 할인 + 쿠폰 안분)이 있는 행은 원수량 기준 누계 비례로 이번 몫을 뺀다(반품과 같은 산식)
                    amount +=
                        com.pmart.claim.domain.ReturnRules.refundAmount(
                            l.path("unitPrice").asLong(),
                            l.path("originalQty").asInt(),
                            l.path("discountShare").asLong(0),
                            refunded,
                            r.qty());
                  }
                  Instant now = clock.instant();
                  String id = "CLM-" + UUID.randomUUID();
                  long items = amount;
                  long refund = Math.max(0, items - Math.max(0, in.feeDeduction()));
                  boolean nothing = refund == 0;
                  ClaimRow c =
                      new ClaimRow(
                              id,
                              in.orderId(),
                              order.path("accountId").asString(),
                              "CANCEL",
                              nothing ? "CLOSED" : "IN_PROGRESS",
                              OUT_OF_STOCK,
                              (in.reasonDetail() == null ? "결품" : in.reasonDetail())
                                  + (in.sourceRef() == null ? "" : " [" + in.sourceRef() + "]"),
                              SYSTEM_CHANNEL,
                              null,
                              1,
                              order.path("paymentAttemptId").asString(),
                              order.path("approvedAmount").asLong(),
                              amount,
                              0,
                              0,
                              null,
                              now,
                              now,
                              nothing ? now : null,
                              now)
                          .withMfcId(order.path("mfcId").asString(null));
                  List<LineRow> rows = new ArrayList<>();
                  int n = 1;
                  for (ClaimRules.Requested r : in.lines()) {
                    JsonNode l = byLine.get(r.orderLineId());
                    rows.add(
                        new LineRow(
                            id + "-L" + n++,
                            id,
                            r.orderLineId(),
                            l.path("sku").asString(),
                            r.qty(),
                            r.qty(),
                            "APPROVED",
                            com.pmart.claim.domain.ReturnRules.refundAmount(
                                l.path("unitPrice").asLong(),
                                l.path("originalQty").asInt(),
                                l.path("discountShare").asLong(0),
                                store.shortageRefundedQty(in.orderId(), r.orderLineId()),
                                r.qty())));
                  }
                  store.insert(c, rows);
                  events.claimOpened(c, rows);
                  if (nothing) {
                    // 공제가 상품값 이상 — 돌려줄 돈이 없다(ADR-0065)
                    log.info("부분 환불 {} 주문 {} 공제 뒤 0원 — 환불 없이 닫음", id, in.orderId());
                    return id;
                  }
                  RefundRow refundRow = refunds.createShortageRefund(c, items, refund, now);
                  String refundId = refundRow.refundIntentId();
                  TransactionSynchronizationManager.registerSynchronization(
                      new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                          refunds.executeAsync(refundId);
                        }
                      });
                  log.info(
                      "결품 부분 환불 {} 주문 {} {}원(공제 {})", id, in.orderId(), refund, items - refund);
                  return id;
                })
            .value();
    return view(claimId);
  }

  private static PmartException unprocessable(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  // ---------- 조회 ----------

  /** 고객은 본인 것만(남의 것 404), 운영자·서비스는 모두. */
  public ClaimView get(PmartPrincipal who, String claimId) {
    ClaimRow c =
        store
            .find(claimId)
            .filter(x -> canSee(who, x))
            .orElseThrow(() -> PmartException.notFound("CLAIM_NOT_FOUND", "클레임이 없습니다."));
    return ClaimView.of(store, c);
  }

  /** 목록 한 쪽(0.6.0 BR-33): 줄, 다음 커서(없으면 끝), 클레임별 환불 의도. */
  public record Page(
      List<ClaimRow> rows,
      @Nullable String nextCursor,
      java.util.Map<String, List<ClaimStore.RefundBrief>> refunds,
      java.util.Map<String, Integer> claimSeqs) {}

  public Page list(
      PmartPrincipal who,
      @Nullable String orderId,
      @Nullable String customerId,
      @Nullable String type,
      @Nullable String state,
      @Nullable String mfcId,
      int size,
      @Nullable String cursor) {
    String account = listAccount(who, customerId);
    String center = listCenter(who, mfcId);
    com.pmart.claim.domain.ClaimListRules.Cursor after = null;
    if (cursor != null && !cursor.isBlank()) {
      after =
          com.pmart.claim.domain.ClaimListRules.decode(cursor)
              .orElseThrow(
                  () ->
                      new PmartException(
                          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다."));
    }
    int limit = Math.max(1, Math.min(size, 100));
    List<ClaimRow> rows = store.list(account, orderId, type, state, center, after, limit + 1);
    String next = null;
    if (rows.size() > limit) {
      rows = rows.subList(0, limit);
      ClaimRow last = rows.get(limit - 1);
      next = com.pmart.claim.domain.ClaimListRules.encode(last.openedAt(), last.claimId());
    }
    List<String> ids = rows.stream().map(ClaimRow::claimId).toList();
    return new Page(List.copyOf(rows), next, store.refundBriefs(ids), store.claimSeqs(ids));
  }

  /**
   * 목록·수의 계정 거르기: 고객은 늘 본인(customerId 무시), 직원·서비스는 customerId(CL-7 0.10.0 — 완전 일치, 없으면 전체). 그 밖
   * 403.
   */
  private static @Nullable String listAccount(PmartPrincipal who, @Nullable String customerId) {
    if (who.type() == PmartPrincipal.PrincipalType.CUSTOMER) {
      return who.subject();
    }
    if (who.type() != PmartPrincipal.PrincipalType.OPERATOR
        && who.type() != PmartPrincipal.PrincipalType.SERVICE) {
      throw new PmartException(com.pmart.common.web.ErrorCode.FORBIDDEN, "클레임을 볼 수 없습니다.");
    }
    return customerId == null || customerId.isBlank() ? null : customerId;
  }

  /** 0.10.0 CL-6: 목록과 같은 거르기(state 제외)로 상태별(byType 이면 종류별) 수. 없는 값은 호출자가 0 으로 채운다. */
  public java.util.Map<String, Long> counts(
      PmartPrincipal who,
      @Nullable String orderId,
      @Nullable String customerId,
      @Nullable String type,
      @Nullable String mfcId,
      boolean byType) {
    return store.counts(
        listAccount(who, customerId), orderId, type, listCenter(who, mfcId), byType);
  }

  /** 0.13.0 CL-8: 센터 거르기는 직원·서비스만(고객 토큰은 무시 — 늘 본인 것 전부). */
  private static @Nullable String listCenter(PmartPrincipal who, @Nullable String mfcId) {
    if (who.type() == PmartPrincipal.PrincipalType.CUSTOMER || mfcId == null || mfcId.isBlank()) {
      return null;
    }
    return mfcId;
  }

  private ClaimView view(String claimId) {
    return ClaimView.of(store, claimId);
  }

  private static boolean canSee(PmartPrincipal who, ClaimRow c) {
    return switch (who.type()) {
      case CUSTOMER -> who.subject().equals(c.accountId());
      case OPERATOR, SERVICE -> true;
      default -> false;
    };
  }

  // ---------- 사건 반영 (inbox 트랜잭션 안) ----------

  /** order.order.v1 — OrderCancelled(claimId) 로 취소 확정·환불 의도. */
  public void onOrderEvent(EventEnvelope e) {
    if (!"OrderCancelled".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String claimId = p.path("claimId").asString(null);
    if (claimId == null || claimId.isBlank()) {
      return; // 클레임 없는 취소(결품 등)는 이 단계에서 다루지 않는다
    }
    ClaimRow c = store.lock(claimId).orElse(null);
    if (c == null || !"STOP_PENDING".equals(c.state())) {
      return; // 이미 반영됐거나 모르는 클레임
    }
    if (!"FULL".equals(p.path("scope").asString())) {
      log.warn("클레임 {} 부분 취소 확정은 아직 다루지 않는다 — 진행 중으로 둔다", claimId);
      return;
    }
    Instant now = clock.instant();
    for (JsonNode l : p.path("lines")) {
      store.decideLine(
          claimId, l.path("orderLineId").asString(), l.path("qty").asInt(), "APPROVED");
    }
    store.updateState(claimId, "IN_PROGRESS", null, now, null, now);
    RefundRow refund = refunds.createFullRefund(c, now);
    if ("PENDING_EXECUTION".equals(refund.state())) {
      String id = refund.refundIntentId();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              refunds.executeAsync(id);
            }
          });
    }
    log.info("클레임 {} 취소 확정 — 환불 {} {}원", claimId, refund.refundIntentId(), refund.amount());
  }

  /** mfc.fulfillment.v1 — WorkStopResult 에 인계됨이 있으면 출고 전 취소 불가(REJECTED). */
  public void onFulfillmentEvent(EventEnvelope e) {
    if (!"WorkStopResult".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String stopRequestId = p.path("stopRequestId").asString(null);
    if (stopRequestId == null) {
      return;
    }
    boolean handedOver = false;
    for (JsonNode pkg : p.path("perPackage")) {
      if ("HANDED_OVER".equals(pkg.path("state").asString())) {
        handedOver = true;
      }
    }
    if (!handedOver) {
      return; // 중지 확정·대기 — 주문 취소 확정(OrderCancelled)을 기다린다
    }
    ClaimRow c = store.lockByStopRequest(stopRequestId).orElse(null);
    if (c == null || !"STOP_PENDING".equals(c.state())) {
      return;
    }
    Instant now = clock.instant();
    store.updateState(c.claimId(), "REJECTED", "HANDED_OVER", now, now, now);
    events.claimClosed(c, "REJECTED_HANDED_OVER", now);
    log.info("클레임 {} 인계된 포장이 있어 출고 전 취소 불가", c.claimId());
  }

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }

  private static PmartException unsupported(String message) {
    return new PmartException(
        "UNSUPPORTED_ACTION", HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }
}
