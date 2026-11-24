"""1호점 부산진구(MFC-SEED-01) 다시 설계 R-45(2026-11-24 사용자 확정 — 시뮬레이터 f2d145c) — 모두 API 로(DB 직접 입력 없음).

요청서 docs/handoff/simulator/mfc/2026-11-24-request-busanjin-redesign.md §0·§3. 칸 코드 체계는 그대로(새 코드 0) — 랙 수·평수·배치만 바뀐다.
  새 위치 파일  docs/simulator/mfc/layout-busanjin-r45/location-positions.json(9,185 = 피킹 8,696·보충 420·SUPPORT 59·대형 10)
  옛 위치 파일  docs/simulator/mfc/layout/location-positions.json(R-38, 15,528) — 옛 − 새 = 없어지는 칸(6,343)
두 파일 어디에도 없는 서버 칸(옛 시험 칸 등 — 거의 CLOSED, RTN-A-01)은 건드리지 않는다.

단계(기본 = 계획만, 쓰기 없음):
  plan       숫자만: 없어질 칸·그 칸의 재고(줄·수량·SKU)·예약/보류 줄·옮길 칸 짝·보충 기준·위치 바꿀 칸.
  move       없어질 칸의 재고를 같은 온도대·구역 글자의 **빈** 칸으로 위치 정정(stock-corrections — 총량·LOT·품질·업무상태 그대로).
             칸 하나 → 빈 칸 하나(칸마다 SKU 하나). 고를 때: 남는 칸·ACTIVE·재고 없음·보충재고 아님·같은 종류(선반/보충) →
             같은 고회전 여부 → 같은 단 → 폭이 원래 칸 이상인 것 중 가장 좁은 것 → 코드 순. 예약·배정·보류가 걸린 줄은 옮기지 않는다(보고).
  close      없어질 칸 중 비어 있는 ACTIVE 칸을 CLOSED(지우지 않음 — 작업·주문 기록이 가리킨다). 재고가 남은 칸은 열어 두고 보고.
  positions  남는 칸 위치(xM·zM)를 새 파일대로 — mfc_layout.py r34 와 같은 일(PMART_POSITIONS = 새 파일).
실행: python -I deploy/lan/seed/mfc_r45.py [plan|move|close|positions ...] [--apply]
      --apply 없이는 어느 단계도 쓰지 않는다. 실행 전 시뮬레이터 1호점 가상 작업자·실주문을 멈춘다(요청서 §0-3).
필요: deploy/lan/.secrets/keycloak-admin-password(센터 관리자 시험 계정 claude-mfc-sup 토큰 — checks/staff_auth.py).
"""
import collections
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
NEW = os.environ.get("PMART_R45_NEW",
                     os.path.join(ROOT, "docs", "simulator", "mfc", "layout-busanjin-r45", "location-positions.json"))
OLD = os.environ.get("PMART_R45_OLD", os.path.join(ROOT, "docs", "simulator", "mfc", "layout", "location-positions.json"))
sys.path.insert(0, HERE)
import mfc_layout as v1  # noqa: E402

if v1.MFC != "MFC-SEED-01":
    raise SystemExit("R-45 는 1호점(MFC-SEED-01) 전용이다")
REASON = "1호점 다시 설계 R-45(2026-11-24 사용자 확정) — 없어지는 칸"
APPROVAL_REF = "layout-r45"


def group(code):
    """온도대 글자 + 구역 글자(F-A2-18-3-01 → F-A, A-R1-03-1-01 → A-R)."""
    p = code.split("-")
    return p[0] + "-" + (p[1][0] if len(p) > 1 and p[1] else "?")


def level(code):
    p = code.split("-")
    return p[3] if len(p) > 3 else ""


def width(loc):
    return ((loc.get("dimensions") or {}).get("widthMm")) or 0


def load():
    new = {x["locationCode"]: x for x in json.load(open(NEW, encoding="utf-8"))}
    old = {x["locationCode"] for x in json.load(open(OLD, encoding="utf-8"))}
    return new, old - set(new)


def live(rows):
    return [r for r in rows if r["qty"] > 0]


def blocked(r):
    return (r.get("allocatedQty") or 0) + (r.get("heldQty") or 0) > 0


def pair_plan(locs, rows, new, gone):
    """없어질 칸(재고 있음) → 빈 남는 칸. 예약·보류 줄이 있는 칸은 통째로 남긴다(일부만 옮기면 칸이 둘로 갈린다)."""
    stock = live(rows)
    occupied = {r["locationCode"] for r in stock}
    by_src = collections.defaultdict(list)
    for r in stock:
        if r["locationCode"] in gone:
            by_src[r["locationCode"]].append(r)
    held = {c for c, ls in by_src.items() if any(blocked(r) for r in ls)}
    free = collections.defaultdict(list)
    for c, n in new.items():
        cur = locs.get(c)
        if (cur and cur["state"] == "ACTIVE" and c not in occupied and n["kind"] in ("SHELF", "RESERVE_RACK")
                and not cur.get("reserveStock")):
            free[group(c)].append(c)
    used, pairs, short = set(), {}, []
    for src in sorted(by_src):
        if src in held:
            continue
        s = locs[src]
        cands = [c for c in free[group(src)] if c not in used and width(locs[c]) >= width(s)]
        if not cands:
            short.append(src)
            continue
        cands.sort(key=lambda c: (bool(locs[c].get("highTurnover")) != bool(s.get("highTurnover")),
                                  level(c) != level(src), width(locs[c]), c))
        pairs[src] = cands[0]
        used.add(cands[0])
    return by_src, held, pairs, short


def policies(s):
    st, b = v1.http("GET", f"{v1.GW}/mfcs/{v1.MFC}/replenishment-policies", s.sup())
    if st != 200:
        print(f"  보충 기준 목록 못 읽음 HTTP {st} {b.get('code')}")
        return None
    return b.get("items", [])


def plan_step(s, locs, rows, new, gone):
    stock = live(rows)
    print(f"새 파일 {len(new)}칸(서버에 없음 {sum(1 for c in new if c not in locs)}), "
          f"상태 {dict(collections.Counter(locs[c]['state'] for c in new if c in locs))}")
    print(f"없어질 칸 {len(gone)}: 상태 {dict(collections.Counter(locs[c]['state'] for c in gone if c in locs))}, "
          f"구역 {dict(sorted(collections.Counter(group(c) for c in gone).items()))}")
    print(f"재고 전체 {len(stock)}줄 {sum(r['qty'] for r in stock)}개 SKU {len({r['sku'] for r in stock})}")
    by_src, held, pairs, short = pair_plan(locs, rows, new, gone)
    g = [r for ls in by_src.values() for r in ls]
    print(f"없어질 칸 재고: 칸 {len(by_src)} · 줄 {len(g)} · 수량 {sum(r['qty'] for r in g)} · SKU {len({r['sku'] for r in g})}"
          f" · 업무상태 {dict(collections.Counter(r['workState'] for r in g))}")
    q = collections.Counter()
    for r in g:
        q[group(r["locationCode"])] += r["qty"]
    print("  구역별 칸·수량:", {k: (sum(1 for c in by_src if group(c) == k), q[k]) for k in sorted(q)})
    print(f"  예약·배정·보류가 걸려 남길 칸 {len(held)}: {sorted(held)[:20]}")
    print(f"  옮길 칸 짝 {len(pairs)}, 빈 칸이 모자람 {len(short)}: {short[:20]}")
    for src, to in list(sorted(pairs.items()))[:8]:
        print(f"    예 {src}(폭 {width(locs[src])}) → {to}(폭 {width(locs[to])})  {[r['sku'] for r in by_src[src]][:1]}")
    same_level = sum(1 for a, b in pairs.items() if level(a) == level(b))
    print(f"  같은 단 {same_level}/{len(pairs)}, 같은 고회전 여부 "
          f"{sum(1 for a, b in pairs.items() if bool(locs[a].get('highTurnover')) == bool(locs[b].get('highTurnover')))}/{len(pairs)}")
    ps = policies(s)
    if ps is not None:
        hit = [p for p in ps if p["locationCode"] in gone]
        print(f"보충 기준 {len(ps)}개 중 없어질 칸 {len(hit)}: {[(p['locationCode'], p['sku']) for p in hit][:20]}")
    moved_pos = sum(1 for c, n in new.items() if c in locs and not v1.same_pos(locs[c].get("position"), n))
    print(f"위치(xM·zM) 바꿀 남는 칸 {moved_pos}/{len(new)}")
    return pairs, held, short


def move_step(s, locs, rows, new, gone, apply):
    by_src, held, pairs, short = pair_plan(locs, rows, new, gone)
    if short:
        raise SystemExit(f"빈 칸이 모자란 칸이 있다 — 옮기지 않음: {short[:20]}")
    print(f"옮길 칸 {len(pairs)}, 남길 칸(예약·보류) {len(held)}")
    if not apply:
        return 0
    failed = 0
    for i, (src, to) in enumerate(sorted(pairs.items()), start=1):
        lines = [{"sku": r["sku"], "lotId": r["lotId"], "fromLocation": src, "qualityState": r["qualityState"],
                  "workState": r["workState"], "qty": r["qty"], "toLocation": to} for r in by_src[src]]
        st, b = v1.http("POST", f"{v1.GW}/mfcs/{v1.MFC}/stock-corrections", s.sup(),
                        {"reason": REASON, "approvalRef": APPROVAL_REF, "lines": lines})
        if st != 201:
            failed += 1
            print("  정정 실패", src, "→", to, st, b.get("code"), b.get("detail"))
        if i % 20 == 0:
            print(f"  … {i}/{len(pairs)} (실패 {failed})", flush=True)
        time.sleep(0.1)
    print(f"옮김 {len(pairs) - failed}/{len(pairs)}, 실패 {failed}")
    return failed


def close_step(s, locs, rows, gone, apply):
    left = {r["locationCode"] for r in live(rows)}
    todo = sorted(c for c in gone if c in locs and locs[c]["state"] == "ACTIVE" and c not in left)
    kept = sorted(c for c in gone if c in left)
    print(f"닫을 칸 {len(todo)}, 재고가 남아 열어 둘 칸 {len(kept)}: {kept[:20]}")
    if not apply:
        return 0
    closed = failed = 0
    for i, c in enumerate(todo, start=1):
        st, b = v1.http("POST", f"{v1.GW}/locations/{locs[c]['locationId']}/state-changes", s.sup(),
                        {"targetState": "CLOSED", "reason": REASON})
        if st == 202:
            closed += 1
        else:
            failed += 1
            if failed <= 10:
                print("  닫기 실패", c, st, b.get("code"), b.get("detail"))
        if i % 500 == 0:
            print(f"  … {i}/{len(todo)} (닫음 {closed}, 실패 {failed})", flush=True)
        time.sleep(0.05)
    print(f"닫음 {closed}, 실패 {failed}")
    return failed


def main():
    for f in (sys.stdout, sys.stderr):
        if hasattr(f, "reconfigure"):
            f.reconfigure(encoding="utf-8", errors="replace")
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    apply = "--apply" in sys.argv
    steps = args or ["plan"]
    new, gone = load()
    s = v1.Session()
    locs = v1.current_locations(s)
    rows = v1.snapshot()
    print(f"[{'실행' if apply else '계획만'}] {v1.MFC} 지금 칸 {len(locs)}, 재고 줄 {len(rows)}")
    failed = 0
    if "plan" in steps:
        plan_step(s, locs, rows, new, gone)
    if "move" in steps:
        failed += move_step(s, locs, rows, new, gone, apply)
        if apply:
            rows = v1.snapshot()
    if "close" in steps:
        failed += close_step(s, locs, rows, gone, apply)
        if apply:
            locs = v1.current_locations(s)
    if "positions" in steps:
        v1.POSITIONS = NEW
        failed += v1.r34_step(s, locs, apply=apply) or 0
    if apply:
        locs = v1.current_locations(s)
        rows = live(v1.snapshot())
        print(f"끝: 칸 상태 {dict(collections.Counter(v['state'] for v in locs.values()))}, "
              f"재고 {len(rows)}줄 {sum(r['qty'] for r in rows)}개, 없어질 칸에 남은 재고 "
              f"{sum(r['qty'] for r in rows if r['locationCode'] in gone)}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
