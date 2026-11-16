"""LAN 시험 센터 재고를 새 칸 구조(mfc_layout_v2) 전체로 펼친다(2026-11-16, 사용자 "규칙대로 펼치기").

지금은 SKU 503종이 온도대마다 첫 구역(냉동 A1·냉장 B1·상온 C1)에 몰려 있다. 규칙(한 상품은 한 자리, 고회전 제외)대로
SKU 마다 한 칸을 골라 그 SKU 의 선반 재고(정상·적치) 전부를 한 번의 위치 정정(stock-corrections, approvalRef layout-v2)으로 옮긴다.

칸 고르기:
  - 온도대가 같은 랙들을 돌아가며(라운드 로빈) 한 SKU 씩 — 랙마다 상품이 고르게 보이게. 단은 3단(허리 높이)부터, 칸은 01부터.
  - 상온: 상품 치수(추정, 상품 서비스 치수 = MFC SKU 사본)가 C~G 칸에 들어가고 용량(칸 부피 × 0.8 ÷ 상품 부피) ≥ 옮길 수량이면 C~G,
    아니면 큰 칸 H. 거기에도 안 맞으면 옮기지 않고 그대로 둔다(보고).
  - 냉장·냉동도 같은 용량 검사. 서버도 같은 검사를 한다(넘치면 409 — 보고하고 다음 SKU).
  - 예약이 걸린 LOT 이 있는 SKU 는 서버가 거절할 수 있다(옛 주문 예약) — 보고만.
사용: python -I deploy/lan/seed/mfc_spread_stock.py           # 계획만
      python -I deploy/lan/seed/mfc_spread_stock.py --apply   # 적용
"""
import collections, csv, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402
import mfc_layout_v2 as v2  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DIMS = os.path.join(HERE, "dimensions-estimated-2026-11-16.csv")
APPROVAL_REF = "layout-v2"
FILL = 0.8


def cell_dims(area_letter):
    levels, slots = v2.DESIGN[area_letter]
    return (round(v2.RACK_LEN * 900 / slots), v2.RACK_DEPTH_MM, round(v2.RACK_HEIGHT * 1000 / levels))


def capacity(item, cell):
    i, c = sorted(item), sorted(cell)
    if any(a > b for a, b in zip(i, c)):
        return 0
    return int(c[0] * c[1] * c[2] * FILL // (item[0] * item[1] * item[2]))


def main(apply):
    dims = {r["sku"]: (int(r["width_mm"]), int(r["depth_mm"]), int(r["height_mm"])) for r in csv.DictReader(open(DIMS, encoding="utf-8"))}
    want = v2.plan()
    racks = collections.defaultdict(list)  # (온도 글자, 구역 첫 글자) → [랙 키]
    for code in want:
        t, area, rack, lv, sl = code.split("-")
        key = (t, area, rack)
        if key not in racks[(t, area[0])]:
            racks[(t, area[0])].append(key)
    rows = [r for r in v1.snapshot() if r["qty"] > 0 and r["workState"] == "STORED" and r["qualityState"] == "NORMAL"]
    by_sku = collections.defaultdict(list)
    for r in rows:
        if v2.is_rack_cell(r["locationCode"]) or r["locationCode"].split("-")[1:2] == ["R1"]:
            by_sku[r["sku"]].append(r)
    used = set()
    cursor = collections.Counter()

    def pick_cell(t, groups, item, qty):
        for g in groups:
            rl = racks[(t, g)]
            if not rl:
                continue
            cell = cell_dims(g)
            if item and capacity(item, cell) < qty:
                continue
            levels, slots = v2.DESIGN[g]
            order = [3, 2, 4, 1, 5, 6][:levels]
            for _ in range(len(rl) * levels * slots):
                k = cursor[(t, g)]
                cursor[(t, g)] += 1
                tt, area, rack = rl[k % len(rl)]
                round_ = k // len(rl)
                lv = order[(round_ // slots) % levels]
                sl = round_ % slots + 1
                code = f"{tt}-{area}-{rack}-{lv}-{sl:02d}"
                if code in want and code not in used:
                    used.add(code)
                    return code
        return None

    groups = {"F": ["A"], "C": ["B"], "A": ["C", "D", "E", "F", "G", "H"]}
    plan, skipped = [], []
    ambient_order = 0
    for sku in sorted(by_sku):
        lines = by_sku[sku]
        t = lines[0]["locationCode"][0]
        qty = sum(r["qty"] for r in lines)
        item = dims.get(sku)
        g = groups[t]
        if t == "A":  # 상온은 C~G 를 돌아가며, 안 되면 H
            rot = ["C", "D", "E", "F", "G"]
            g = rot[ambient_order % 5:] + rot[:ambient_order % 5] + ["H"]
            ambient_order += 1
        dest = pick_cell(t, g, item, qty)
        if dest is None:
            skipped.append((sku, qty, item))
            continue
        plan.append((sku, dest, lines))
    dest_area = collections.Counter(d.split("-")[1][0] for _, d, _ in plan)
    print(f"옮길 SKU {len(plan)}, 못 옮김 {len(skipped)}, 구역별 {dict(sorted(dest_area.items()))}")
    for s in skipped[:10]:
        print("  못 옮김(칸·용량)", s)
    if not apply:
        return
    s = v1.Session()
    ok = failed = 0
    for i, (sku, dest, lines) in enumerate(plan):
        body = {"reason": "MFC 문서 칸 설계(layout-v2)로 바꾼 뒤 재고를 랙 전체로 펼침 2026-11-16", "approvalRef": APPROVAL_REF,
                "lines": [{"sku": sku, "lotId": r["lotId"], "fromLocation": r["locationCode"], "qualityState": r["qualityState"],
                           "workState": r["workState"], "qty": r["qty"], "toLocation": dest} for r in lines]}
        st, b = v1.http("POST", f"{v1.GW}/mfcs/{v1.MFC}/stock-corrections", s.sup(), body)
        if st in (200, 201):
            ok += 1
        else:
            failed += 1
            if failed <= 15:
                print("  실패", sku, "→", dest, st, b.get("code"), (b.get("detail") or "")[:120])
        if (i + 1) % 100 == 0:
            print(f"  {i + 1}/{len(plan)} (성공 {ok}, 실패 {failed})", flush=True)
        time.sleep(0.06)
    print(f"옮김 {ok}, 실패 {failed}, 못 옮김(계획 단계) {len(skipped)}")


if __name__ == "__main__":
    main("--apply" in sys.argv)
