package com.pmart.mfc.fulfillment.domain;

import com.pmart.mfc.fulfillment.domain.TaskRules.Content;
import com.pmart.mfc.fulfillment.domain.TaskRules.ContentRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 재포장 규칙(WK-07). 인계 전(SEALED)만, 인계 진행(살아 있는 fence·결과 대기 인계)·중지 기록이 있는 주문은 거절. 내용물은 바꾸지 않는다 — 포장 사이에
 * 상품을 옮기거나 줄이는 재구성은 재고 PACK 되돌림이 필요해 아직 없다(CONTENT_MISMATCH).
 */
public final class RepackRules {

  public static final Set<String> REASONS =
      Set.of("DAMAGED_BAG", "CONTENT_CHANGE", "LABEL_UNREADABLE", "SHORTAGE_ADJUST");

  private RepackRules() {}

  /**
   * 인계 허가가 만료됐지만 실제로 넘기지 않았다는 확인이 없는 포장(D-1 미결). 확인 절차가 정해질 때까지 재포장하지 않는다 — MFC 1553행 "실제로 넘어가지
   * 않았는지 확인한 뒤". 2026-11-08 검토 반영(기본값 #3).
   */
  public static final String FENCE_EXPIRED_UNCONFIRMED = HandoverRules.FENCE_EXPIRED_UNCONFIRMED;

  /**
   * 거절 코드. null 이면 재포장 가능.
   *
   * @param fenceOpen 인계 허가 기록이 열려 있음(HANDOVER_IN_PROGRESS — 살아 있든 만료됐든)
   */
  public static @Nullable String rejection(
      String packageState,
      boolean fenceLive,
      boolean fenceOpen,
      boolean handoverRequested,
      boolean orderStopped) {
    if ("HANDED_OVER".equals(packageState)) {
      return HandoverRules.ALREADY_HANDED_OVER;
    }
    if (!"SEALED".equals(packageState)) {
      return HandoverRules.INVALID_STATE;
    }
    if (handoverRequested) {
      return HandoverRules.HANDOVER_RESULT_PENDING;
    }
    if (fenceLive) {
      return HandoverRules.FENCE_ACTIVE;
    }
    if (fenceOpen) {
      return FENCE_EXPIRED_UNCONFIRMED;
    }
    if (orderStopped) {
      return HandoverRules.INVALID_STATE;
    }
    return null;
  }

  public static String message(String code, String packageState) {
    return switch (code) {
      case HandoverRules.ALREADY_HANDED_OVER -> "이미 인계된 포장은 재포장할 수 없습니다.";
      case HandoverRules.HANDOVER_RESULT_PENDING -> "인계 확정 결과를 기다리는 포장입니다.";
      case HandoverRules.FENCE_ACTIVE -> "라이더 인계가 진행 중인 포장입니다(허가 유효).";
      case FENCE_EXPIRED_UNCONFIRMED ->
          "인계 허가가 만료됐지만 실제로 넘기지 않았다는 확인이 없습니다 — 점원 확인(handover-checks) 뒤 재포장합니다.";
      default -> "재포장할 수 없는 상태입니다(포장 " + packageState + ", 또는 주문 중지).";
    };
  }

  /**
   * 요청 내용물이 지금 내용물과 (주문 줄·SKU·LOT 별 수량으로) 같은지. 생략·빈 목록이면 같은 것으로 본다. LOT 을 생략한 줄은 그 주문 줄·SKU 의 합으로
   * 비교한다.
   *
   * @return 다르면 사유, 같으면 null
   */
  public static @Nullable String contentMismatch(
      List<Content> current, @Nullable List<ContentRequest> requested) {
    if (requested == null || requested.isEmpty()) {
      return null;
    }
    boolean withLot = requested.stream().allMatch(r -> r.lotId() != null);
    Map<String, Integer> have = new TreeMap<>();
    for (Content c : current) {
      have.merge(key(c.orderLineId(), c.sku(), withLot ? c.lotId() : null), c.qty(), Integer::sum);
    }
    Map<String, Integer> want = new TreeMap<>();
    for (ContentRequest r : requested) {
      if (r.qty() < 1) {
        return "수량은 1 이상입니다: " + r.orderLineId();
      }
      want.merge(key(r.orderLineId(), r.sku(), withLot ? r.lotId() : null), r.qty(), Integer::sum);
    }
    if (!have.equals(want)) {
      return "재포장은 내용물을 바꾸지 않습니다(지금 " + have + ", 요청 " + want + "). 포장 사이 재구성·수량 감소는 아직 지원하지 않습니다.";
    }
    return null;
  }

  /**
   * 포장 사이 재구성(C-12 1부): 새 포장들의 (주문 줄·SKU·LOT) 합이 이전 포장들의 합과 정확히 같은가. 새 줄은 LOT 필수·수량 1 이상.
   *
   * @return 거절 사유, 괜찮으면 null
   */
  public static @Nullable String regroupMismatch(
      List<Content> current, List<List<ContentRequest>> newPackages) {
    Map<String, Integer> have = new TreeMap<>();
    for (Content c : current) {
      have.merge(key(c.orderLineId(), c.sku(), c.lotId()), c.qty(), Integer::sum);
    }
    Map<String, Integer> want = new TreeMap<>();
    for (List<ContentRequest> pkg : newPackages) {
      if (pkg.isEmpty()) {
        return "새 포장에 내용물이 없습니다.";
      }
      for (ContentRequest r : pkg) {
        if (r.lotId() == null || r.lotId().isBlank()) {
          return "재구성은 줄마다 lotId 가 필요합니다: " + r.orderLineId();
        }
        if (r.qty() < 1) {
          return "수량은 1 이상입니다: " + r.orderLineId();
        }
        want.merge(key(r.orderLineId(), r.sku(), r.lotId()), r.qty(), Integer::sum);
      }
    }
    if (!have.equals(want)) {
      return "재구성은 합계를 바꾸지 않습니다(지금 " + have + ", 요청 " + want + "). 수량 감소는 아직 지원하지 않습니다.";
    }
    return null;
  }

  private static String key(String orderLineId, String sku, @Nullable String lotId) {
    return orderLineId + "|" + sku + (lotId == null ? "" : "|" + lotId);
  }
}
