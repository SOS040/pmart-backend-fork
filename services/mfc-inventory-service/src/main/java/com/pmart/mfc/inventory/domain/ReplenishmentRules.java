package com.pmart.mfc.inventory.domain;

/**
 * 자동 선반 보충 수량(IV-04, MFC 문서의 확정 산식 — 값만 Q-07 미결). 순수 계산.
 *
 * <p>현재량 C ≤ 최소 min 이면 필요량 N = max(0, 목표 − C − 진행 중 보충 U), 지시량 = floor(min(N, 출발 이동 가능 S, 도착 남은 공간
 * V) ÷ 이동 단위 u) × u. 그 밖에는 0. 문서 수용 기준: C2·min2·target10·U3·S8·V6·u1 → 5.
 */
public final class ReplenishmentRules {

  private ReplenishmentRules() {}

  public static int orderQty(int c, int min, int target, int u, int s, int v, int unit) {
    if (unit <= 0 || c > min) {
      return 0;
    }
    int n = Math.max(0, target - c - u);
    int m = Math.min(n, Math.min(Math.max(0, s), Math.max(0, v)));
    return (m / unit) * unit;
  }
}
