package com.pmart.mfc.fulfillment.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 점원 단말 작업 규칙(WK-03·WK-05·WK-07, 슬라이스 6b). 순수 계산.
 *
 * <ul>
 *   <li>시작 점유: READY·ASSIGNED 이고 활성 담당 = 요청 작업자·배정 버전이 같을 때만. PACK 은 PICK 완료가 선행.
 *   <li>포장 LOT 배정: 주문 줄마다 집품했지만 아직 포장하지 않은 예약 조각을 소비기한 빠른 순으로.
 * </ul>
 */
public final class TaskRules {

  private TaskRules() {}

  /** PDA 피킹 표시(0.4.9): OK·PARTIAL·NOT_FOUND·DONE 과 화면 안내. */
  public record PickStatus(
      String status,
      @org.jspecify.annotations.Nullable Integer unallocatedQty,
      @org.jspecify.annotations.Nullable String notice) {}

  /**
   * @param remaining 이 LOT 조각에서 더 집을 양
   * @param shelfQty 후보 칸들의 그 LOT 선반 잔량 합
   * @param allocated 이 주문 몫으로 칸에 배정된 양 합(피킹 시작 때 배정)
   * @param started 피킹 시작됨(배정이 끝난 뒤)
   */
  public static PickStatus pickStatus(int remaining, int shelfQty, int allocated, boolean started) {
    if (remaining <= 0) {
      return new PickStatus("DONE", 0, null);
    }
    int covered = started ? allocated : Math.min(remaining, shelfQty);
    int missing = Math.max(0, remaining - covered);
    if (missing == 0) {
      return new PickStatus("OK", 0, null);
    }
    if (covered == 0) {
      return new PickStatus("NOT_FOUND", missing, "어느 칸에서도 못 찾음 — 센터 관리자 확인");
    }
    return new PickStatus("PARTIAL", missing, missing + "개는 칸을 못 찾음 — 있는 만큼 집고 센터 관리자 확인");
  }

  public static final String ALREADY_STARTED = "ALREADY_STARTED";
  public static final String NOT_ASSIGNEE = "NOT_ASSIGNEE";
  public static final String PREDECESSOR_NOT_COMPLETED = "PREDECESSOR_NOT_COMPLETED";
  public static final String INVALID_STATE = "INVALID_STATE";
  public static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  /** 활성 담당(mfc-capacity 정본 동기 조회). 없으면 null. */
  /**
   * @param workerRejection 배정 작업자가 지금 맡을 수 없는 이유(capacity 조회 때 계산 — 정지·퇴사·업무 변경), null 이면 적격
   */
  public record ActiveAssignment(
      String workerId, int assignmentVersion, @Nullable String workerRejection) {
    public ActiveAssignment(String workerId, int assignmentVersion) {
      this(workerId, assignmentVersion, null);
    }
  }

  /**
   * 시작 판정. null = STARTED, 아니면 거절 코드.
   *
   * @param predecessorCompleted PACK 의 선행(PICK) 완료 여부. PICK 은 true
   */
  public static @Nullable String startRejection(
      String taskState,
      boolean predecessorCompleted,
      @Nullable ActiveAssignment active,
      String workerId,
      int assignmentVersion) {
    if ("IN_PROGRESS".equals(taskState)) {
      return ALREADY_STARTED;
    }
    if ("WAITING".equals(taskState) && !predecessorCompleted) {
      return PREDECESSOR_NOT_COMPLETED;
    }
    if (!"READY".equals(taskState) && !"ASSIGNED".equals(taskState)) {
      return INVALID_STATE;
    }
    if (active == null || !active.workerId().equals(workerId)) {
      return NOT_ASSIGNEE;
    }
    if (active.assignmentVersion() != assignmentVersion) {
      return VERSION_CONFLICT;
    }
    if (active.workerRejection() != null) {
      return active.workerRejection(); // 배정 뒤 정지·퇴사·업무 변경 — 센터 관리자가 재배정(2026-11-08 검토 #29·#30)
    }
    return null;
  }

  /** 집품 조각(inventory pick-plan). room = 집품 − 포장. */
  public record PickedPart(
      String orderLineId, String sku, String lotId, @Nullable String expiresOn, int room) {}

  /** 포장 내용 한 줄(요청). lotId 가 있으면 그 LOT 만. */
  public record ContentRequest(String orderLineId, String sku, @Nullable String lotId, int qty) {}

  /** 포장 내용 확정 줄. */
  public record Content(String orderLineId, String sku, String lotId, int qty) {}

  /** LOT 배정 결과. error 가 있으면 contents 는 쓰지 않는다. */
  public record Packing(List<Content> contents, @Nullable String error) {}

  public static Packing allocatePacking(List<ContentRequest> requests, List<PickedPart> parts) {
    Map<String, Integer> used = new java.util.HashMap<>();
    List<Content> out = new ArrayList<>();
    for (ContentRequest r : requests) {
      List<PickedPart> cands =
          parts.stream()
              .filter(p -> p.orderLineId().equals(r.orderLineId()) && p.sku().equals(r.sku()))
              .filter(p -> r.lotId() == null || p.lotId().equals(r.lotId()))
              .sorted(
                  Comparator.comparing(
                          PickedPart::expiresOn,
                          Comparator.nullsLast(Comparator.<String>naturalOrder()))
                      .thenComparing(PickedPart::lotId))
              .toList();
      int need = r.qty();
      for (PickedPart p : cands) {
        String k = p.orderLineId() + "|" + p.lotId();
        int left = p.room() - used.getOrDefault(k, 0);
        if (left <= 0 || need == 0) {
          continue;
        }
        int take = Math.min(left, need);
        used.merge(k, take, Integer::sum);
        out.add(new Content(r.orderLineId(), r.sku(), p.lotId(), take));
        need -= take;
      }
      if (need > 0) {
        return new Packing(
            List.of(),
            "주문 줄 "
                + r.orderLineId()
                + " 은 집품했지만 포장하지 않은 수량이 "
                + (r.qty() - need)
                + " 입니다(요청 "
                + r.qty()
                + ").");
      }
    }
    return new Packing(out, null);
  }
}
