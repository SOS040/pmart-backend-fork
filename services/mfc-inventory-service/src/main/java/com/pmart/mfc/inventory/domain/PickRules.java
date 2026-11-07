package com.pmart.mfc.inventory.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 피킹·포장 실적을 예약 조각에 연결(IV-07). 순수 계산.
 *
 * <ul>
 *   <li>실적 줄(주문 줄·LOT·수량)은 같은 주문 줄·같은 LOT 의 CONFIRMED 조각에만 붙는다 — 예약에 없는 LOT 은 LOT_MISMATCH(다른 로트 집품
 *       차단, IV-07).
 *   <li>PICK 은 조각의 남은 집품량(qty − picked), PACK 은 남은 포장량(picked − packed) 안에서. 넘으면 PICK_QTY_EXCEEDED.
 *   <li>같은 요청의 앞 줄이 쓴 양은 뒤 줄에서 빠진다. 조각은 주어진 순서(part_id 순)로 채운다.
 * </ul>
 */
public final class PickRules {

  private PickRules() {}

  public static final String LOT_MISMATCH = "LOT_MISMATCH";
  public static final String LINE_NOT_IN_RESERVATION = "LINE_NOT_IN_RESERVATION";
  public static final String PICK_QTY_EXCEEDED = "PICK_QTY_EXCEEDED";
  public static final String PART_NOT_CONFIRMED = "PART_NOT_CONFIRMED";

  public enum Step {
    PICK,
    PACK
  }

  /** 예약 조각의 지금 값. */
  public record Part(
      String partId,
      @Nullable String orderLineId,
      String lotId,
      boolean confirmed,
      int qty,
      int picked,
      int packed) {

    int room(Step step) {
      return step == Step.PICK ? qty - picked : picked - packed;
    }
  }

  /** 조각에 붙인 양. */
  public record Take(String partId, int qty) {}

  /** 결과: error 가 있으면 takes 는 쓰지 않는다. */
  public record Result(List<Take> takes, @Nullable String error, @Nullable String detail) {

    static Result fail(String error, String detail) {
      return new Result(List.of(), error, detail);
    }
  }

  /**
   * @param used 같은 요청 앞 줄들이 조각별로 이미 쓴 양(이 메서드가 갱신한다)
   */
  public static Result allocate(
      Step step,
      String orderLineId,
      String lotId,
      int qty,
      List<Part> parts,
      Map<String, Integer> used) {
    List<Part> line = parts.stream().filter(p -> orderLineId.equals(p.orderLineId())).toList();
    if (line.isEmpty()) {
      return Result.fail(LINE_NOT_IN_RESERVATION, "주문 줄 " + orderLineId + " 는 이 예약에 없습니다.");
    }
    List<Part> sameLot = line.stream().filter(p -> lotId.equals(p.lotId())).toList();
    if (sameLot.isEmpty()) {
      return Result.fail(
          LOT_MISMATCH,
          "주문 줄 "
              + orderLineId
              + " 의 예약 LOT 이 아닙니다: "
              + lotId
              + " (예약 LOT "
              + line.stream().map(Part::lotId).distinct().toList()
              + ")");
    }
    List<Part> confirmed = sameLot.stream().filter(Part::confirmed).toList();
    if (confirmed.isEmpty()) {
      return Result.fail(
          PART_NOT_CONFIRMED, "주문 줄 " + orderLineId + " 의 LOT " + lotId + " 예약이 확정 상태가 아닙니다.");
    }
    int need = qty;
    int room = 0;
    List<Take> takes = new ArrayList<>();
    for (Part p : confirmed) {
      int left = p.room(step) - used.getOrDefault(p.partId(), 0);
      if (left <= 0) {
        continue;
      }
      room += left;
      if (need > 0) {
        int take = Math.min(need, left);
        takes.add(new Take(p.partId(), take));
        need -= take;
      }
    }
    if (need > 0) {
      return Result.fail(
          PICK_QTY_EXCEEDED,
          "주문 줄 "
              + orderLineId
              + " LOT "
              + lotId
              + ": 요청 "
              + qty
              + " > 남은 "
              + (step == Step.PICK ? "집품" : "포장")
              + "량 "
              + room);
    }
    for (Take t : takes) {
      used.merge(t.partId(), t.qty(), Integer::sum);
    }
    return new Result(takes, null, null);
  }
}
