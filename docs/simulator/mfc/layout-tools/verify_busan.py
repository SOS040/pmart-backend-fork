"""부산 진구점 463평 — 구역 면적에서 실제로 놓을 수 있는 랙 수를 기하 규칙으로 계산한다(2026-11-21 검증 문서의 표).

규칙은 build_layout.py 와 같다(랙 1.25 × 0.45 m, 통로 0.7 m, 12랙 넘는 줄은 가운데 횡단 1.2 m, 줄 끝 여유).
실행: python -I docs/simulator/mfc/layout-tools/verify_busan.py
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_layout import PY, RD, AISLE, RW, max_rows, max_run, run_len, across  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def need_block(n, per_row_max, ends):
    """랙 n 개를 줄 길이 per_row_max 이하로 놓을 때 가장 작은 블록(폭 × 길이) — 줄 수 r, 한 줄 k."""
    best = None
    for r in range(1, 60):
        k = math.ceil(n / r)
        if k > per_row_max:
            continue
        # 폭: S + D… (+ 끝 A 또는 S)
        nd, rem = divmod(r - 1, 2)
        w = RD + nd * (AISLE + 2 * RD) + (AISLE + RD if rem else AISLE)
        length = run_len(k)[0]
        a = w * (length + ends)
        if best is None or a < best[0]:
            best = (a, r, k, w, length)
    return best


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    L = json.load(open(os.path.join(os.path.dirname(HERE), "layout", "layout.json"), encoding="utf-8"))
    zones = {z["id"]: z["rect"] for z in L["zones"]}
    print("== 1) 배치 사각형(layout.json) 안 최대 랙 수")
    # 냉동·냉장: 줄 = 남북(z). 북쪽 끝 여유 1.0, 남쪽은 보충 팔레트·격리 띠(z ≥ 26.4)를 빼고 문 쪽 여유 1.3
    for zid, name, n_doc in (("A", "냉동", 105), ("B", "냉장", 125)):
        r = zones[zid]
        w = r["x1"] - r["x0"]
        length = 26.4 - 1.0 - 1.3
        k = max_run(length)
        rows = max_rows(w)
        print(f"  {name}: 폭 {w:.1f} m → 줄 {rows}, 줄 길이 {length:.1f} m → 한 줄 {k}랙 → 최대 {rows * k} (문서 {n_doc}, 평면 {sum(1 for x in L['racks'] if x['group'][0] == zid)})")
    r = zones["C-H"]
    w = r["x1"] - r["x0"]
    d = r["z1"] - r["z0"]
    k = max_run(w - 0.8 - 1.0)
    rows = max_rows(d - 1.5 - 0.0)
    print(f"  상온: 줄 = 동서, 길이 {w:.1f}-0.8-1.0 → 한 줄 {k}랙, 깊이 {d:.1f}-1.5(남쪽 주 통로) → 줄 {rows} → 최대 {rows * k} (문서 138, 평면 138)")
    print("== 2) 문서 랙 수를 담는 가장 작은 블록 vs 문서 면적(4-3 피킹 몫·상온 65평)")
    for name, n, per_row_max, ends, doc_py in (("냉동", 105, 18, 2.3, 50), ("냉장", 125, 18, 2.3, 59), ("상온", 138, 9, 1.8, 65)):
        tot, r, k, w, length = need_block(n, per_row_max, ends)
        ok = "≤" if tot / PY <= doc_py else ">"
        print(f"  {name} {n}랙: {r}줄 × {k}랙, 랙 블록 {w:.2f} × {length:.2f} m, 줄 끝 여유 {ends} m 포함 {tot:.1f} ㎡ = {tot / PY:.1f}평 {ok} 문서 {doc_py}평")
        print(f"      랙 바닥 {n * RW * RD:.1f} ㎡ = {n * RW * RD / PY:.1f}평")
    print("== 3) 칸(슬롯) 수용량")
    for name, n, lv, sl, sku in (("냉동", 105, 5, 6, 2180), ("냉장", 125, 5, 7, 3270)):
        s = n * lv * sl
        print(f"  {name}: {n} × {lv} × {sl} = {s:,} → 85 % {s * 0.85:,.1f} vs SKU {sku:,} → 여유 {s * 0.85 - sku:,.1f}; 최소 랙 = 올림({sku}/({lv}×{sl}×0.85)) = {math.ceil(sku / (lv * sl * 0.85))}")
    tot = 0
    for g, n, lv, sl, sku in (("C", 24, 6, 9, 1050), ("D", 24, 6, 10, 1100), ("E", 21, 6, 8, 800), ("F", 24, 6, 10, 1100), ("G", 25, 6, 9, 1050), ("H", 20, 5, 5, 350)):
        s = n * lv * sl
        tot += s
        print(f"  상온 {g}: {n} × {lv} × {sl} = {s:,} → 85 % {s * 0.85:,.1f} vs {sku:,}; 최소 랙 {math.ceil(sku / (lv * sl * 0.85))}")
    print(f"  상온 합계 {tot:,} → 85 % {tot * 0.85:,.1f} vs 5,450")
    print("== 4) 평면 구역 넓이(㎡·평)")
    for z in L["zones"]:
        rr = z["rect"]
        a = (rr["x1"] - rr["x0"]) * (rr["z1"] - rr["z0"])
        print(f"  {z['id']:5s} {a:7.2f} ㎡ {a / PY:6.2f}평 (문서 {z.get('docPyeong', '-')})")
    inside = sum((z["rect"]["x1"] - z["rect"]["x0"]) * (z["rect"]["z1"] - z["rect"]["z0"]) for z in L["zones"]
                 if z["kind"] not in ("SHIPPING", "PARKING", "QUARANTINE"))
    b = L["width"] * L["buildingDepth"]
    print(f"  건물 {b} ㎡ = {b / PY:.1f}평, 구역(격리 제외 — 격리는 다른 구역 안) {inside:.1f} ㎡, 나머지(공용 통로·벽) {b - inside:.1f} ㎡ = {(b - inside) / PY:.1f}평")


if __name__ == "__main__":
    main()
