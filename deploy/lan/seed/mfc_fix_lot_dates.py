"""LAN 시험 센터 LOT 날짜 정리(2026-11-16, 사용자 "소비기한 지워" · "재고 소비기한 일괄 정정 해줘").

seed-v2(11-07)가 모든 LOT 에 소비기한을 붙였고 시간이 지나 일부는 출고할 수 없게 됐다. 위치 정정(stock-corrections)으로
같은 칸·같은 수량을 새 추적 LOT 으로 옮긴다(재고 수는 그대로, 원장에 CORRECTION 기록, approvalRef 아래).

1) 비식품(상품 카테고리 문구·사무용품, 생활용품 — 입력 파일 nonfood.txt `sku|이름`): 소비기한이 있는 LOT →
   소비기한·제조일 없는 LOT(source_lot_ref NO-EXPIRY-2026-11-16). 이 상품들은 소비기한 보장이 없어 그대로 배정된다.
2) 식품 중 출고할 수 없는 LOT(배정 규칙 LotAllocator 와 같게, 약속일 = 오늘 + 2일로 판정):
   - 잔여일 보장 min 이 있는데 소비기한 없음, 또는 소비기한 − 약속일 < min(만료 포함)
   - 제조 후 경과일 보장 max 가 있는데 제조일 없음, 또는 약속일 − 제조일 > max
   → 새 소비기한 = 오늘 + max(90, min + 60)일, 제조 보장이 있는 상품은 제조일 = 오늘, 없으면 원래 제조일 유지.
   source_lot_ref = RESTAMP-2026-11-16.
배정·보류된 수량(allocatedQty·heldQty)은 옮기지 않는다(주문이 잡고 있음 — 보고).
사용: python -I deploy/lan/seed/mfc_fix_lot_dates.py <nonfood.txt> <guar_lots.txt> [--apply]
guar_lots.txt = `sku|min|max` 줄 + `LOT-…|제조일` 줄(읽기 전용 DB 조회).
"""
import collections, datetime as dt, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402

TODAY = dt.date(2026, 11, 16)
PROMISE = TODAY + dt.timedelta(days=2)
REF_NOEXP, REF_RESTAMP = "NO-EXPIRY-2026-11-16", "RESTAMP-2026-11-16"


def d(s):
    return dt.date.fromisoformat(s) if s else None


def main(nonfood_path, guar_path, apply):
    nonfood = {l.split("|")[0] for l in open(nonfood_path, encoding="utf-8") if "|" in l}
    guar, mfg = {}, {}
    for l in open(guar_path, encoding="utf-8"):
        p = l.strip().split("|")
        if p[0].startswith("LOT"):
            mfg[p[0]] = d(p[1])
        elif len(p) == 3:
            guar[p[0]] = (int(p[1]) if p[1] else None, int(p[2]) if p[2] else None)
    rows = [r for r in v1.snapshot() if r["qty"] > 0 and r["workState"] == "STORED"]
    plans = collections.defaultdict(list)  # sku → [(row, movable, toLine)]
    held = 0
    kinds = collections.Counter()
    expired = 0
    for r in rows:
        sku, exp, m = r["sku"], d(r.get("expiryDate")), mfg.get(r["lotId"])
        movable = r["qty"] - (r.get("allocatedQty") or 0) - (r.get("heldQty") or 0)
        mn, mx = guar.get(sku, (None, None))
        if sku in nonfood:
            if exp is None and m is None:
                continue
            extra = {"toSourceLotRef": REF_NOEXP}
            kind = "nonfood"
        else:
            bad = False
            if mn is not None and (exp is None or (exp - PROMISE).days < mn):
                bad = True
            if mx is not None and (m is None or (PROMISE - m).days > mx):
                bad = True
            if not bad:
                continue
            new_exp = TODAY + dt.timedelta(days=max(90, (mn or 0) + 60))
            new_mfg = TODAY if mx is not None else m
            extra = {"toSourceLotRef": REF_RESTAMP, "toExpiryDate": new_exp.isoformat()}
            if new_mfg:
                extra["toManufactureDate"] = new_mfg.isoformat()
            kind = "restamp"
            expired += exp is not None and exp < TODAY
        if movable <= 0:
            held += r["qty"]
            continue
        held += r["qty"] - movable
        kinds[kind] += 1
        plans[(kind, sku)].append({"sku": sku, "lotId": r["lotId"], "fromLocation": r["locationCode"], "qualityState": r["qualityState"],
                                   "workState": r["workState"], "qty": movable, **extra})
    print(f"비식품 LOT {kinds['nonfood']}(SKU {sum(1 for k in plans if k[0] == 'nonfood')}), "
          f"출고 불가 식품 LOT {kinds['restamp']}(SKU {sum(1 for k in plans if k[0] == 'restamp')}, 그중 만료 {expired}), "
          f"주문이 잡고 있어 남김 {held}개")
    if not apply:
        return
    s = v1.Session()
    ok = failed = 0
    for (kind, sku), lines in sorted(plans.items()):
        reason = ("비식품 LOT 소비기한 제거(seed-v2 가 붙인 것) 2026-11-16" if kind == "nonfood"
                  else "시험 재고 소비기한 일괄 정정(출고 불가 LOT, seed-v2) 2026-11-16")
        st, b = v1.http("POST", f"{v1.GW}/mfcs/{v1.MFC}/stock-corrections", s.sup(),
                        {"reason": reason, "approvalRef": "lot-dates-2026-11-16", "lines": lines})
        if st in (200, 201):
            ok += 1
        else:
            failed += 1
            if failed <= 10:
                print("  실패", sku, st, b.get("code"), (b.get("detail") or "")[:150])
        time.sleep(0.06)
    print(f"정정 SKU 성공 {ok}, 실패 {failed}")


if __name__ == "__main__":
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    main(a[0], a[1], "--apply" in sys.argv)
