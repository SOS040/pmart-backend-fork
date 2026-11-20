package com.pmart.claim.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 반품(인계 뒤 회수) 규칙 — CL-1, D-7·ADR-0060 결정 2, 사용자 결정 2026-11-17 "추천대로". 프레임워크 없음.
 *
 * <ul>
 *   <li>접수는 고객센터(CS_AGENT·OPERATOR_ADMIN)만. 반품 가능 수량 = 인계 수량(라이더 픽업·전달) − 이 주문의 진행·완료 반품 수량.
 *   <li>회수비 9,000원(Delivery 381행) — 회사 잘못 사유 다섯 가지면 면제. 회수비는 상품 환불과 상계하지 않고 따로 결제.
 *   <li>1차 감액 없음: 검수 수량(RESALE·DISPOSE 모두) × 원단가 − 할인 안분 전액 환불(봉투값·배달팁 없음).
 *   <li>회수 실패: 첫 실패는 자동으로 한 번 더, 두 번째 시도부터 실패하면 상담원 재연락(RECOVERY_FAILED). 회수비는 한 번만.
 * </ul>
 *
 * 추가 결정(2026-11-17 사용자 "추천대로" 1~9):
 *
 * <ul>
 *   <li>회수 시도는 전체 3번(자동 2 + 상담원 재시도 1) — 넘으면 상담원은 반품 취소만. 라이더·우리 잘못(RIDER_INCIDENT)은 횟수에서 빼고 바로 다시
 *       요청.
 *   <li>회수 실패로 반품을 취소할 때 낸 회수비: 고객 사유 → 돌려주지 않음, 우리 잘못 → 돌려줌, 파손·기타 → 상담원 판단(기본 돌려주지 않음).
 *   <li>반품 전체 기한 = 접수 + 7일(설정) — 그 안에 회수가 안 되면 자동 취소.
 *   <li>MFC 가 요청보다 적게 받으면 상담원이 "받은 만큼만 끝내기"(남은 수량 환불 없음).
 * </ul>
 */
public final class ReturnRules {

  private ReturnRules() {}

  /** 회수비(원) — 업무 원문 Delivery 381행, D-7(사용자 "응 그대로"). */
  public static final long RECOVERY_FEE = 9_000;

  /** 자동으로 내는 회수 요청 수(사용자 2026-11-17 "2번 시도 뒤 상담원 재연락"). 횟수에 드는 실패만 센다. */
  public static final int AUTO_PICKUP_ATTEMPTS = 2;

  /** 회수 시도 전체 수(자동 2 + 상담원 재시도 1 — 추가 결정 3). 횟수에 드는 실패가 이만큼이면 상담원은 반품 취소만. */
  public static final int MAX_PICKUP_ATTEMPTS = 3;

  /** 고객 사유 회수 실패(delivery 전달 실패 코드 — ReturnPickupFailed.reason, customerReason true). */
  public static final Set<String> CUSTOMER_FAILURE_REASONS =
      Set.of("CUSTOMER_ABSENT", "UNREACHABLE", "ADDRESS_ERROR", "REFUSED");

  /** 라이더·우리 잘못 회수 실패 — 횟수에서 빼고 바로 다시 요청(추가 결정 4), 취소되면 회수비 돌려줌(추가 결정 2). */
  public static final String RIDER_INCIDENT = "RIDER_INCIDENT";

  // 회수 실패 종류(return_pickup.failure_kind)
  public static final String FAIL_CUSTOMER = "CUSTOMER";
  public static final String FAIL_OURS = "OURS";
  public static final String FAIL_OTHER = "OTHER";

  // 반품 취소 때 회수비 결정(return_fee.refund_decision)
  public static final String FEE_REFUND = "REFUND";
  public static final String FEE_KEEP = "KEEP";

  /** 회수비 돌려주기 환불 의도 사유(refund_intent.reason — payment 취소 사유는 RETURN). */
  public static final String RETURN_FEE_REFUND_REASON = "RETURN_FEE";

  /** 반품 취소 때 낸 회수비 규칙(추가 결정 2). */
  public enum FeeRefundRule {
    /** 우리 잘못만 있었다(또는 실패 없이 기한이 지났다) — 돌려준다. */
    REFUND,
    /** 고객 사유 실패가 있었다 — 돌려주지 않는다. */
    KEEP,
    /** 파손·기타 실패 — 상담원 판단(기본 돌려주지 않음). */
    CS_DECIDES
  }

  /** 결제창 표시용 주문명. */
  public static final String FEE_ORDER_NAME = "반품 회수비";

  public static final String CS_AGENT = "CS_AGENT";
  public static final String OPERATOR_ADMIN = "OPERATOR_ADMIN";

  /** 반품 접수·후속 조치를 할 수 있는 역할(고객센터 — CS 접수와 같은 역할). */
  public static final Set<String> STAFF_ROLES = Set.of(CS_AGENT, OPERATOR_ADMIN);

  /** 배차 불가 취소 사유(CL-5, 2026-11-19) — 회사 사유라 본인 확인 없음, 상품준비 뒤라도 인계 전이면. */
  public static final String DISPATCH_UNAVAILABLE = "DISPATCH_UNAVAILABLE";

  /**
   * 관리자 판단 취소 사유(2026-11-20, ADR-0076 — 사용자 "관리자 사유 취소만 넓힘", MFC 시뮬레이터 R-29). 배차 불가와 같은 범위(상품준비 뒤라도
   * 인계 전, 본인 확인 없음)이고 사유 메모(reasonDetail)가 필수다. 역할은 그 센터 MFC_SUPERVISOR·OPERATOR_ADMIN 만(CS_AGENT
   * 제외). 고객 취소 범위는 그대로.
   */
  public static final String OPERATOR_DECISION = "OPERATOR_DECISION";

  /** 직원 회사 사유 전체 취소(인계 전이면 상품준비 뒤라도) — 배차 불가·관리자 판단. */
  public static final Set<String> COMPANY_CANCEL_REASONS =
      Set.of(DISPATCH_UNAVAILABLE, OPERATOR_DECISION);

  /** 배차 불가 취소를 할 수 있는 센터 역할(그 센터 권한이 있어야 — CS·운영 관리자는 센터 무관). */
  public static final String MFC_SUPERVISOR = "MFC_SUPERVISOR";

  /** 회사 잘못 — 회수비 면제(사용자 2026-11-17 ②). */
  public static final Set<String> FAULT_CODES =
      Set.of("MISDELIVERY", "DEFECTIVE", "DAMAGED_IN_DELIVERY", "SEVERE_DELAY", "WRONG_SUBSTITUTE");

  public static final String ORDER_DETAILS_MATCH = "ORDER_DETAILS_MATCH";
  public static final String APP_SIGNED_IN_CHAT = "APP_SIGNED_IN_CHAT";
  public static final String OTHER = "OTHER";

  public static final Set<String> STAFF_CHANNELS = Set.of("CS", "OPERATOR");

  // 클레임 상태(RETURN)
  public static final String FEE_PENDING = "FEE_PENDING";
  public static final String IN_PROGRESS = "IN_PROGRESS";
  public static final String RECOVERY_FAILED = "RECOVERY_FAILED";
  public static final String CANCELLED = "CANCELLED";
  public static final String CLOSED = "CLOSED";

  // 회수비 상태
  public static final String FEE_WAITING = "WAITING";
  public static final String FEE_PAID = "PAID";
  public static final String FEE_UNPAID = "UNPAID";
  public static final String FEE_LATE_CANCEL_PENDING = "LATE_CANCEL_PENDING";
  public static final String FEE_LATE_CANCELLED = "LATE_CANCELLED";
  public static final String FEE_LATE_CANCEL_FAILED = "LATE_CANCEL_FAILED";

  /** 결과가 아직 안 난 결제 상태(payment PaymentState) — 기한이 지나도 결과를 기다린다(오배송 다시 배송비와 같은 규칙). */
  private static final Set<String> IN_FLIGHT = Set.of("PENDING", "UNKNOWN", "MISMATCH");

  /** 새 결제 시도가 필요한 상태. CREATED·PENDING 은 그대로 쓴다. */
  private static final Set<String> DEAD = Set.of("FAILED", "CANCELLED", "PARTIALLY_CANCELLED");

  /** 원장 행(심사·환불 근거). */
  public record OrderLine(
      String orderLineId,
      String sku,
      long unitPrice,
      int originalQty,
      int deliveredQty,
      long discountShare) {}

  /** 요청 행. */
  public record Requested(String orderLineId, int qty) {}

  /** 행별 반품 가능 수량 심사. 통과하면 아무것도 돌려주지 않고, 아니면 {@link ClaimRules.Rejection}. */
  public static void checkReturnable(
      List<OrderLine> lines, List<Requested> requested, Map<String, Integer> occupied) {
    Map<String, OrderLine> byId = new HashMap<>();
    int delivered = 0;
    for (OrderLine l : lines) {
      byId.put(l.orderLineId(), l);
      delivered += l.deliveredQty();
    }
    // 사용자 2026-11-17 "배달 중이나 전달 완료 전에는 반품 불가" — 인계(라이더 픽업)가 아니라 고객 전달 완료 수량으로 본다
    if (delivered == 0) {
      throw new ClaimRules.Rejection(
          "NOT_DELIVERED_YET", "고객에게 전달 완료된 상품이 없습니다 — 반품(회수)은 전달 완료 뒤에만 접수합니다.");
    }
    Map<String, Integer> seen = new HashMap<>();
    for (Requested r : requested) {
      OrderLine l = byId.get(r.orderLineId());
      if (l == null) {
        throw new ClaimRules.Rejection(
            "QUANTITY_EXCEEDS_AVAILABLE", "주문에 없는 상품입니다: " + r.orderLineId());
      }
      if (seen.put(r.orderLineId(), r.qty()) != null) {
        throw new ClaimRules.Rejection(
            "QUANTITY_EXCEEDS_AVAILABLE", "같은 상품이 두 번 들어 있습니다: " + r.orderLineId());
      }
      if (r.qty() < 1) {
        throw new ClaimRules.Rejection("QUANTITY_EXCEEDS_AVAILABLE", "수량은 1 이상입니다.");
      }
      int available = l.deliveredQty() - occupied.getOrDefault(r.orderLineId(), 0);
      if (l.deliveredQty() == 0) {
        throw new ClaimRules.Rejection(
            "NOT_DELIVERED_YET", "아직 전달 완료되지 않은 상품입니다: " + r.orderLineId());
      }
      if (r.qty() > available) {
        throw new ClaimRules.Rejection(
            "QUANTITY_EXCEEDS_AVAILABLE",
            "반품 가능한 수량("
                + Math.max(0, available)
                + " = 전달 완료 "
                + l.deliveredQty()
                + " − 진행·완료 반품 "
                + occupied.getOrDefault(r.orderLineId(), 0)
                + ")보다 많습니다: "
                + r.orderLineId());
      }
    }
  }

  /** 본인 확인 기록 검사 — 어긋나면 사람이 읽을 이유, 맞으면 null(case CS 접수와 같은 값). */
  public static @Nullable String identityRejection(@Nullable String method, @Nullable String note) {
    if (method == null || method.isBlank()) {
      return "본인 확인 방법(identityCheck.method)이 필요합니다.";
    }
    if (!Set.of(ORDER_DETAILS_MATCH, APP_SIGNED_IN_CHAT, OTHER).contains(method)) {
      return "알 수 없는 본인 확인 방법입니다: " + method;
    }
    if (OTHER.equals(method) && (note == null || note.isBlank())) {
      return "본인 확인 방법이 기타면 어떻게 확인했는지(identityCheck.note)를 적어야 합니다.";
    }
    return null;
  }

  /** 회수비 — 회사 잘못 사유가 있으면 0(면제). */
  public static long fee(@Nullable String faultCode) {
    return faultCode == null ? RECOVERY_FEE : 0;
  }

  public static boolean feeWaived(@Nullable String faultCode) {
    return faultCode != null;
  }

  /** 결제 주문 참조 — 원래 주문 결제와 별개(같은 주문에 승인된 결제가 있어도 새 시도를 막지 않게). */
  public static String feeChargeRef(String claimId) {
    return "RTF-" + claimId;
  }

  public static String feeRequestId(String claimId, int attemptNo) {
    return claimId + ":FEE:" + attemptNo;
  }

  public static String pickupRequestId(String claimId, int attemptNo) {
    return claimId + "-P" + attemptNo;
  }

  public static boolean feeInFlight(@Nullable String paymentState) {
    return paymentState != null && IN_FLIGHT.contains(paymentState);
  }

  public static boolean feeDead(@Nullable String paymentState) {
    return paymentState != null && DEAD.contains(paymentState);
  }

  public static boolean expired(Instant deadline, Instant now) {
    return !now.isBefore(deadline);
  }

  /**
   * 횟수에 드는 실패가 이만큼일 때 자동으로 다음 회수를 요청하나(첫 실패만). 그 밖은 상담원 재연락.
   *
   * @param countedFailures 횟수에 드는(우리 잘못이 아닌) 실패 수 — 방금 실패 포함
   */
  public static boolean autoRetryAfterFailure(int countedFailures) {
    return countedFailures < AUTO_PICKUP_ATTEMPTS;
  }

  /** 상담원이 다시 회수를 요청할 수 있나(전체 3번 — 추가 결정 3). */
  public static boolean csRetryAllowed(int countedFailures) {
    return countedFailures < MAX_PICKUP_ATTEMPTS;
  }

  /**
   * 회수 실패 종류. RIDER_INCIDENT 는 우리 잘못(OURS), 고객 사유 코드 또는 customerReason true 면 CUSTOMER, 그 밖(파손
   * DAMAGED·기타 OTHER·모르는 코드)은 OTHER.
   */
  public static String failureKind(@Nullable String reason, @Nullable Boolean customerReason) {
    if (RIDER_INCIDENT.equals(reason)) {
      return FAIL_OURS;
    }
    if (Boolean.TRUE.equals(customerReason)
        || (reason != null && CUSTOMER_FAILURE_REASONS.contains(reason))) {
      return FAIL_CUSTOMER;
    }
    return FAIL_OTHER;
  }

  /** 이 실패가 회수 시도 횟수에 드나(우리 잘못은 빼고). 종류를 모르면(null) 센다. */
  public static boolean countsAsAttempt(@Nullable String failureKind) {
    return !FAIL_OURS.equals(failureKind);
  }

  /** 횟수에 드는 실패 수. */
  public static int countedFailures(List<@Nullable String> failureKinds) {
    return (int) failureKinds.stream().filter(ReturnRules::countsAsAttempt).count();
  }

  /**
   * 반품을 취소할 때 낸 회수비 규칙(추가 결정 2). 이 반품의 회수 실패 종류 전부로 본다: 고객 사유가 하나라도 있으면 KEEP, 아니고 파손·기타(또는 종류 모름)가
   * 있으면 CS_DECIDES, 우리 잘못뿐이거나 실패가 없으면(회수하러 가지 못한 채 기한) REFUND.
   */
  public static FeeRefundRule feeRefundRule(List<@Nullable String> failureKinds) {
    if (failureKinds.contains(FAIL_CUSTOMER)) {
      return FeeRefundRule.KEEP;
    }
    for (String k : failureKinds) {
      if (!FAIL_OURS.equals(k)) {
        return FeeRefundRule.CS_DECIDES;
      }
    }
    return FeeRefundRule.REFUND;
  }

  /**
   * 회수비를 돌려주나. 상담원 선택(csChoice)은 CS_DECIDES 에서만 쓰고, 정해진 규칙과 다른 값이면 {@link ClaimRules.Rejection}
   * FEE_REFUND_NOT_ALLOWED. 선택이 없으면 규칙 기본(CS_DECIDES 는 돌려주지 않음).
   */
  public static boolean refundFee(FeeRefundRule rule, @Nullable Boolean csChoice) {
    return switch (rule) {
      case REFUND -> {
        if (Boolean.FALSE.equals(csChoice)) {
          throw new ClaimRules.Rejection(
              "FEE_REFUND_NOT_ALLOWED", "우리 쪽 잘못(RIDER_INCIDENT)으로 회수하지 못한 반품은 회수비를 돌려줍니다.");
        }
        yield true;
      }
      case KEEP -> {
        if (Boolean.TRUE.equals(csChoice)) {
          throw new ClaimRules.Rejection(
              "FEE_REFUND_NOT_ALLOWED", "고객 사유(부재·연락 두절·주소 오류·거부)로 회수하지 못한 반품은 회수비를 돌려주지 않습니다.");
        }
        yield false;
      }
      case CS_DECIDES -> Boolean.TRUE.equals(csChoice);
    };
  }

  /** 반품 전체 기한이 지났나(기한 없으면 아니다 — 기한은 접수 때 정한다). */
  public static boolean pastDeadline(@Nullable Instant deadline, Instant now) {
    return deadline != null && !now.isBefore(deadline);
  }

  /** "받은 만큼만 끝내기" 판정용 클레임 행. */
  public record ShortLine(
      String claimLineId, int requestedQty, int receivedQty, int inspectedQty) {}

  /**
   * "받은 만큼만 끝내기"(추가 결정 6): 행마다 정산 수량 = 받은 수량(반입·검수 중 큰 값, 요청 수량까지). 받은 것이 없으면 NOTHING_RECEIVED(반품
   * 취소를 쓴다), 모든 행을 다 받았으면 NOT_SHORT(검수·환불 뒤 저절로 끝난다) — {@link ClaimRules.Rejection}.
   *
   * @return claimLineId → 정산 수량
   */
  public static Map<String, Integer> shortClose(List<ShortLine> lines) {
    Map<String, Integer> out = new LinkedHashMap<>();
    int got = 0;
    boolean shortOne = false;
    for (ShortLine l : lines) {
      int settled = Math.min(l.requestedQty(), Math.max(l.receivedQty(), l.inspectedQty()));
      out.put(l.claimLineId(), settled);
      got += settled;
      shortOne |= settled < l.requestedQty();
    }
    if (got == 0) {
      throw new ClaimRules.Rejection(
          "NOTHING_RECEIVED", "MFC 가 받은 회수품이 없습니다 — 받은 만큼만 끝낼 수 없습니다(반품 취소를 쓰세요).");
    }
    if (!shortOne) {
      throw new ClaimRules.Rejection("NOT_SHORT", "요청 수량을 모두 받았습니다 — 검수·환불이 끝나면 저절로 종결됩니다.");
    }
    return out;
  }

  /**
   * 검수 수량 환불액 = 단가 × 수량 − 할인 안분. 할인은 원수량 기준 누계 비례(원 미만 버림) — 앞서 검수한 수량까지의 몫을 빼서 이번 몫만. 원수량을 다 돌려주면
   * 할인 합이 정확히 discountShare 가 된다.
   *
   * @param inspectedBefore 이 행에서 이미 환불 근거가 된(검수된) 수량
   */
  public static long refundAmount(
      long unitPrice, int originalQty, long discountShare, int inspectedBefore, int qty) {
    if (qty <= 0) {
      return 0;
    }
    long discount = 0;
    if (discountShare > 0 && originalQty > 0) {
      long after = Math.floorDiv(discountShare * (inspectedBefore + qty), originalQty);
      long before = Math.floorDiv(discountShare * inspectedBefore, originalQty);
      discount = after - before;
    }
    return Math.max(0, unitPrice * qty - discount);
  }

  /** 클레임 행(배분 대상). */
  public record ClaimLineSlot(String claimLineId, String orderLineId, String sku, int room) {}

  /**
   * 들어온 수량(MFC 반입·검수 줄)을 클레임 행에 나눈다. orderLineId 가 있으면 그 행, 없으면 같은 SKU 행을 앞에서부터(남은 칸 room 까지). 넘는
   * 수량은 버린다(경고는 호출자).
   *
   * @return claimLineId → 배분 수량
   */
  public static Map<String, Integer> allocate(
      List<ClaimLineSlot> slots, @Nullable String orderLineId, String sku, int qty) {
    Map<String, Integer> out = new LinkedHashMap<>();
    int left = qty;
    List<ClaimLineSlot> candidates = new ArrayList<>();
    for (ClaimLineSlot s : slots) {
      if (orderLineId != null ? orderLineId.equals(s.orderLineId()) : sku.equals(s.sku())) {
        candidates.add(s);
      }
    }
    for (ClaimLineSlot s : candidates) {
      if (left <= 0) {
        break;
      }
      int take = Math.min(left, Math.max(0, s.room()));
      if (take > 0) {
        out.merge(s.claimLineId(), take, Integer::sum);
        left -= take;
      }
    }
    return out;
  }
}
