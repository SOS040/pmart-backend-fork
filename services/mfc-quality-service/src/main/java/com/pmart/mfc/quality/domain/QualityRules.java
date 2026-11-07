package com.pmart.mfc.quality.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** 품질 판정·폐기 규칙(MFC IV-09·IV-10, Q-11). 요청자와 승인자는 다른 사람. 폐기는 승인 잔량 안에서만 실행 — 승인만으로 현물을 줄이지 않는다. */
public final class QualityRules {

  public static final String WORKER = "MFC_WORKER";
  public static final String SUPERVISOR = "MFC_SUPERVISOR";
  public static final String SELF_APPROVAL = "SELF_APPROVAL";
  public static final String QTY_EXCEEDS_APPROVED = "QTY_EXCEEDS_APPROVED";

  public static final Set<String> REASONS =
      Set.of(
          "TEMPERATURE",
          "DAMAGE",
          "EXPIRY",
          "RECALL",
          "INSPECTION_FAIL",
          "INSPECTION_PASS",
          "OTHER");

  private QualityRules() {}

  /** 판정·폐기 줄. workState 는 재고 단위 행 업무 상태(STORED·UNSTOWED, null = STORED). */
  public record Line(
      String sku, String lotId, String locationCode, int qty, @Nullable String workState) {

    public String key() {
      return sku
          + "|"
          + lotId
          + "|"
          + locationCode
          + "|"
          + (workState == null ? "STORED" : workState);
    }
  }

  /** 폐기 승인 줄. */
  public record DisposalLine(
      String sku,
      String lotId,
      String locationCode,
      @Nullable String workState,
      int requestedQty,
      int approvedQty,
      int executedQty) {

    public String key() {
      return new Line(sku, lotId, locationCode, 0, workState).key();
    }

    public int remaining() {
      return approvedQty - executedQty;
    }
  }

  /**
   * 줄 검증.
   *
   * @return 위반 사유, 없으면 null
   */
  public static @Nullable String invalid(List<Line> lines) {
    if (lines.isEmpty() || lines.size() > 200) {
      return "줄은 1~200개입니다.";
    }
    for (Line l : lines) {
      if (l.qty() < 1) {
        return "수량은 1 이상입니다: " + l.sku();
      }
      if (l.workState() != null && !Set.of("STORED", "UNSTOWED").contains(l.workState())) {
        return "workState 는 STORED/UNSTOWED 입니다: " + l.sku();
      }
    }
    return null;
  }

  /** 같은 단위 키 줄을 합친다(순서 유지). */
  public static Map<String, Line> merge(List<Line> lines) {
    Map<String, Line> out = new LinkedHashMap<>();
    for (Line l : lines) {
      out.merge(
          l.key(),
          l,
          (a, b) ->
              new Line(a.sku(), a.lotId(), a.locationCode(), a.qty() + b.qty(), a.workState()));
    }
    return out;
  }

  /**
   * 부분 승인: 승인 줄이 없으면 요청 전량, 있으면 줄마다 요청량 이하.
   *
   * @return 승인 줄, 또는 위반이면 IllegalArgumentException
   */
  public static List<DisposalLine> approve(
      List<DisposalLine> requested, @Nullable List<Line> approved) {
    List<DisposalLine> out = new ArrayList<>();
    if (approved == null || approved.isEmpty()) {
      for (DisposalLine d : requested) {
        out.add(
            new DisposalLine(
                d.sku(),
                d.lotId(),
                d.locationCode(),
                d.workState(),
                d.requestedQty(),
                d.requestedQty(),
                0));
      }
      return out;
    }
    Map<String, Line> want = merge(approved);
    for (DisposalLine d : requested) {
      Line a = want.remove(d.key());
      int qty = a == null ? 0 : a.qty();
      if (qty > d.requestedQty()) {
        throw new IllegalArgumentException(
            "승인량 " + qty + " > 요청량 " + d.requestedQty() + " (" + d.sku() + " " + d.lotId() + ")");
      }
      out.add(
          new DisposalLine(
              d.sku(), d.lotId(), d.locationCode(), d.workState(), d.requestedQty(), qty, 0));
    }
    if (!want.isEmpty()) {
      throw new IllegalArgumentException("요청에 없는 줄을 승인할 수 없습니다: " + want.keySet());
    }
    if (out.stream().mapToInt(DisposalLine::approvedQty).sum() == 0) {
      throw new IllegalArgumentException("승인량이 0 입니다 — 반려(REJECT)로 처리하세요.");
    }
    return out;
  }

  /**
   * 실행 가능 검사: 줄마다 (승인 − 실행 누계 − 진행 중 실행) 이상이면 안 된다.
   *
   * @param pending 진행 중 실행의 단위 키별 수량
   * @return 위반 사유, 없으면 null
   */
  public static @Nullable String exceeds(
      List<DisposalLine> approved, Map<String, Integer> pending, List<Line> execute) {
    Map<String, DisposalLine> byKey = new LinkedHashMap<>();
    approved.forEach(d -> byKey.put(d.key(), d));
    for (Line l : merge(execute).values()) {
      DisposalLine d = byKey.get(l.key());
      if (d == null) {
        return "승인되지 않은 줄입니다: " + l.sku() + " " + l.lotId() + " @" + l.locationCode();
      }
      int room = d.remaining() - pending.getOrDefault(l.key(), 0);
      if (l.qty() > room) {
        return "실행 "
            + l.qty()
            + " > 남은 승인 "
            + Math.max(0, room)
            + " ("
            + l.sku()
            + " "
            + l.lotId()
            + ")";
      }
    }
    return null;
  }

  /** 실행 누계를 더한 승인 줄과 다음 상태(COMPLETED / PARTIALLY_EXECUTED). */
  public static List<DisposalLine> addExecuted(List<DisposalLine> approved, List<Line> executed) {
    Map<String, Line> done = merge(executed);
    List<DisposalLine> out = new ArrayList<>();
    for (DisposalLine d : approved) {
      Line e = done.get(d.key());
      out.add(
          e == null
              ? d
              : new DisposalLine(
                  d.sku(),
                  d.lotId(),
                  d.locationCode(),
                  d.workState(),
                  d.requestedQty(),
                  d.approvedQty(),
                  d.executedQty() + e.qty()));
    }
    return out;
  }

  public static String stateAfterExecution(List<DisposalLine> lines) {
    return lines.stream().allMatch(d -> d.remaining() == 0) ? "COMPLETED" : "PARTIALLY_EXECUTED";
  }
}
