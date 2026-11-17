"""LAN 시험 센터 LOT 변주 — LOT 이 1개뿐인 상품 일부를 같은 칸에서 두 LOT(기한 다름)으로 나눈다(2026-11-17, 사용자 "일부만 나눔").

배경: LOT 단위 바코드(GS1-128, IV-4) 시험에 "같은 상품, 다른 LOT" 이 필요하다. 503 상품 중 307 은 이미 LOT 이 2~6개이고
196 은 1개뿐이다. 그중 옮길 수 있는 수량(보유 − 배정 − 보류)이 MIN_QTY 이상인 상품만, 그 LOT 의 절반을 새 추적 LOT 으로 옮긴다.
- 위치 정정(stock-corrections, approvalRef lot-split-2026-11-17): 같은 칸·같은 품질·적치 상태 그대로, 재고 수 그대로(원장 CORRECTION).
- 새 LOT: 소비기한이 있으면 원래 + SHIFT_DAYS 일(나중에 들어온 묶음), 제조일이 있으면 원래 + SHIFT_DAYS 일(오늘을 넘지 않게).
  기한 없는 LOT(비식품)은 날짜 없이 원천 LOT 표시만 다르게(source_lot_ref LOT-SPLIT-2026-11-17-B).
- 배정·보류된 수량은 옮기지 않는다(주문이 잡고 있음).
사용: python -I deploy/lan/seed/mfc_split_lots.py            # 계획만
      python -I deploy/lan/seed/mfc_split_lots.py --apply    # 적용
"""
import collections, datetime as dt, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402

TODAY = dt.date(2026, 11, 17)
MIN_QTY = 4
SHIFT_DAYS = 7
REF = "LOT-SPLIT-2026-11-17-B"


def d(s):
    return dt.date.fromisoformat(s) if s else None


def main(apply):
    rows = [r for r in v1.snapshot() if r["qty"] > 0]
    by_sku = collections.defaultdict(list)
    for r in rows:
        by_sku[r["sku"]].append(r)
    plan = []
    single = 0
    for sku, rs in sorted(by_sku.items()):
        if len({r["lotId"] for r in rs}) != 1:
            continue
        single += 1
        stored = [r for r in rs if r["workState"] == "STORED" and r["qualityState"] == "NORMAL"]
        if not stored:
            continue
        r = max(stored, key=lambda x: x["qty"] - (x.get("allocatedQty") or 0) - (x.get("heldQty") or 0))
        movable = r["qty"] - (r.get("allocatedQty") or 0) - (r.get("heldQty") or 0)
        if movable < MIN_QTY:
            continue
        line = {"sku": sku, "lotId": r["lotId"], "fromLocation": r["locationCode"], "qualityState": r["qualityState"],
                "workState": r["workState"], "qty": movable // 2, "toSourceLotRef": REF}
        exp = d(r.get("expiryDate"))
        if exp:
            line["toExpiryDate"] = (exp + dt.timedelta(days=SHIFT_DAYS)).isoformat()
        mfg = d(r.get("manufactureDate"))
        if mfg:
            line["toManufactureDate"] = min(TODAY, mfg + dt.timedelta(days=SHIFT_DAYS)).isoformat()
        plan.append(line)
    dated = sum(1 for p in plan if "toExpiryDate" in p)
    print(f"LOT 1개 상품 {single}, 나눌 상품 {len(plan)}(기한 있는 LOT {dated}, 기한 없는 LOT {len(plan) - dated}), "
          f"옮기는 수량 합 {sum(p['qty'] for p in plan)}")
    for p in plan[:5]:
        print("  예", p["sku"], p["fromLocation"], p["qty"], p.get("toExpiryDate"))
    if not apply:
        return
    s = v1.Session()
    ok = failed = 0
    for line in plan:
        st, b = v1.http("POST", f"{v1.GW}/mfcs/{v1.MFC}/stock-corrections", s.sup(),
                        {"reason": "LOT 변주(같은 상품 다른 LOT — LOT 바코드 시험) 2026-11-17", "approvalRef": "lot-split-2026-11-17",
                         "lines": [line]})
        if st in (200, 201):
            ok += 1
        else:
            failed += 1
            if failed <= 10:
                print("  실패", line["sku"], st, b.get("code"), (b.get("detail") or "")[:150])
        time.sleep(0.06)
    print(f"나눔 성공 {ok}, 실패 {failed}")


if __name__ == "__main__":
    main("--apply" in sys.argv)
