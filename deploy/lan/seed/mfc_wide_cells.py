"""칸에 안 들어가는 상품을 넓은 칸으로(2026-11-18, 사용자 "칸 구조 바꿔서라도 들어갈 수 있게" → "이대로 해줘").

11-16 칸 구조(mfc_layout_v2)로 펼친 뒤에도 칸 용량(칸 부피 × 0.8 ÷ 상품 부피)을 넘는 상품이 냉동 A·냉장 B·상온 H 에 남았다.
같은 단의 빈 칸 몇 개를 하나로 합쳐(첫 칸 코드의 폭을 넓히고 나머지는 닫음) 그 상품을 한 자리에 넣는다 — "한 상품 한 자리" 그대로.
한 단 전체로도 안 되는 큰 상품(화장지 30롤 등)은 상온 H 랙 하나의 아래 단들을 위아래로 합친다(선반을 뺀 셈 — 높이 = 단 수 × 단 높이).
임시 자리 MIGRATION 에 있는 작은 상품은 같은 온도대의 빈 보통 칸으로 옮긴다.

하는 일(모두 API — DB 직접 입력 없음):
  1) 합친 칸: 첫 칸의 치수를 바꾼다(PUT /locations/{id}), 합쳐진 나머지 칸은 닫는다(빈 칸만 — state-changes CLOSED).
  2) 재고를 위치 정정으로 옮긴다(stock-corrections, approvalRef layout-v3-wide, 총량 불변).
  3) 계획을 wide-cells-2026-11-18.json 에 남긴다 — mfc_layout_v2 가 다시 돌아도 합친 칸을 되돌리지 않게 읽는다.
고르는 칸: 단의 모든 칸이 비어 있는 단만(재고 0, 열린 칸). E2E 시험 고회전 칸이 있는 랙 A-H1-01 은 쓰지 않는다. 아래 단부터.
사용: python -I deploy/lan/seed/mfc_wide_cells.py           # 계획만
      python -I deploy/lan/seed/mfc_wide_cells.py --apply   # 적용
"""
import collections, csv, json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402
import mfc_layout_v2 as v2  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DIMS = os.path.join(HERE, "dimensions-estimated-2026-11-16.csv")
PLAN_FILE = os.path.join(HERE, "wide-cells-2026-11-18.json")
APPROVAL_REF = "layout-v3-wide"
FILL = 0.8
SKIP_RACKS = {"A-H1-01"}  # E2E 시험 고회전 칸(A-H1-01-1-01~04·2-01)
LEVEL_ORDER = [1, 2, 4, 5, 3, 6]  # 아래 단부터(크고 무거운 상품), 허리 높이 3단은 보통 칸에 남겨 둔다
GROUP_OF_TEMP = {"F": "A", "C": "B", "A": "H"}  # 넓은 칸을 만드는 그룹
NORMAL_GROUPS = {"F": ["A"], "C": ["B"], "A": ["C", "D", "E", "F", "G", "H"]}
STORAGE_LETTER = {"FROZEN": "F", "CHILLED": "C", "AMBIENT": "A"}


def cell(area_letter, slots=1, levels=1):
    lv, sl = v2.DESIGN[area_letter]
    return (round(v2.RACK_LEN * 900 / sl) * slots, v2.RACK_DEPTH_MM, round(v2.RACK_HEIGHT * 1000 / lv) * levels)


def capacity(item, c):
    i, cc = sorted(item), sorted(c)
    if any(a > b for a, b in zip(i, cc)):
        return 0
    return int(cc[0] * cc[1] * cc[2] * FILL // (item[0] * item[1] * item[2]))


def build():
    dims, temp = {}, {}
    for r in csv.DictReader(open(DIMS, encoding="utf-8")):
        dims[r["sku"]] = (int(r["width_mm"]), int(r["depth_mm"]), int(r["height_mm"]))
        temp[r["sku"]] = STORAGE_LETTER.get(r["storage"])
    rows = [r for r in v1.snapshot() if r["qty"] > 0]
    occupied = {r["locationCode"] for r in rows}
    by_sku = collections.defaultdict(list)
    for r in rows:
        by_sku[r["sku"]].append(r)
    want = v2.plan()  # 랙 칸 전부(코드 → 등록 본문)

    # 넓힐 상품·옮길 작은 상품
    wide, small = [], []
    for sku, lines in sorted(by_sku.items()):
        item = dims.get(sku)
        locs = {r["locationCode"] for r in lines}
        qty = sum(r["qty"] for r in lines)
        if not item:
            continue
        rack_locs = [c for c in locs if v2.is_rack_cell(c)]
        if locs == {"MIGRATION"}:
            t = temp.get(sku)
            if t:
                small.append((sku, t, qty, item, lines))
            continue
        if len(rack_locs) != 1 or not locs <= {rack_locs[0], "MIGRATION"}:
            print(f"  (건너뜀 — 여러 자리) {sku} {sorted(locs)}")
            continue  # 랙 여러 칸에 나뉜 상품은 이 스크립트 대상이 아님
        code = rack_locs[0]
        area = code.split("-")[1][0]
        if "MIGRATION" not in locs and capacity(item, cell(area)) >= qty:
            continue
        # 랙 칸 + MIGRATION 에 나뉜 상품은 합계로 본다(한 자리로 모은다)
        if "MIGRATION" in locs and capacity(item, cell(area)) >= qty:
            small.append((sku, code[0], qty, item, lines))
            continue
        t = code[0]
        g = GROUP_OF_TEMP[t]
        slots_per_level = v2.DESIGN[g][1]
        k = next((n for n in range(1, slots_per_level + 1) if capacity(item, cell(g, n)) >= qty), None)
        vertical = None
        if k is None:
            vertical = next((n for n in range(2, v2.DESIGN[g][0] + 1) if capacity(item, cell(g, slots_per_level, n)) >= qty), None)
        wide.append({"sku": sku, "temp": t, "group": g, "qty": qty, "item": item, "from": code, "slots": k,
                     "levels": vertical, "lines": lines})

    # 빈 단 찾기: 랙(온도 글자, 구역, 랙 번호) → 단 → 칸 번호들(전부 비어 있고 계획에 있는 칸)
    racks = collections.defaultdict(lambda: collections.defaultdict(list))
    for code in want:
        t, area, rack, lv, sl = code.split("-")
        racks[(t, area, rack)][int(lv)].append(int(sl))
    free_levels = collections.defaultdict(list)  # 그룹 → [(rack key, 단)]
    for key, levels in sorted(racks.items()):
        t, area, rack = key
        if f"{t}-{area}-{rack}" in SKIP_RACKS:
            continue
        for lv in LEVEL_ORDER:
            if lv not in levels:
                continue
            codes = [f"{t}-{area}-{rack}-{lv}-{s:02d}" for s in sorted(levels[lv])]
            if any(c in occupied for c in codes):
                continue
            free_levels[area[0]].append((key, lv))
    used_levels = set()
    merges, moves, unplaced = [], [], []

    # 위아래로 합치는 큰 상품 먼저: 같은 랙의 아래 단 n 개가 모두 비어 있어야 한다
    for w in [x for x in wide if x["levels"]]:
        g = w["group"]
        racks_free = collections.defaultdict(set)
        for key, lv in free_levels[g]:
            if (key, lv) not in used_levels:
                racks_free[key].add(lv)
        pick = next((key for key, lvs in sorted(racks_free.items()) if set(range(1, w["levels"] + 1)) <= lvs), None)
        if not pick:
            unplaced.append((w["sku"], "위아래로 합칠 빈 랙 없음"))
            continue
        t, area, rack = pick
        slots = v2.DESIGN[g][1]
        head = f"{t}-{area}-{rack}-1-01"
        absorbed = [f"{t}-{area}-{rack}-{lv}-{s:02d}" for lv in range(1, w["levels"] + 1) for s in range(1, slots + 1)]
        absorbed.remove(head)
        for lv in range(1, w["levels"] + 1):
            used_levels.add((pick, lv))
        merges.append({"code": head, "dims": cell(g, slots, w["levels"]), "absorbed": absorbed, "sku": w["sku"],
                       "kind": f"폭 전체 × {w['levels']}단 높이"})
        moves.append((w, head))

    # 옆으로 합치는 상품: 넓은 것부터, 이미 쓰기 시작한 단을 먼저 채운다
    level_next = {}  # (rack key, 단) → 다음 빈 칸 번호
    for w in sorted([x for x in wide if x["slots"]], key=lambda x: (-x["slots"], x["sku"])):
        g = w["group"]
        slots = v2.DESIGN[g][1]
        cand = [kl for kl in level_next if kl[0][1][0] == g and level_next[kl] + w["slots"] - 1 <= slots]
        if not cand:
            nxt = next((kl for kl in free_levels[g] if kl not in used_levels and kl not in level_next), None)
            if not nxt:
                unplaced.append((w["sku"], "빈 단 없음"))
                continue
            level_next[nxt] = 1
            used_levels.add(nxt)
            cand = [nxt]
        kl = cand[0]
        (t, area, rack), lv = kl
        start = level_next[kl]
        head = f"{t}-{area}-{rack}-{lv}-{start:02d}"
        absorbed = [f"{t}-{area}-{rack}-{lv}-{s:02d}" for s in range(start + 1, start + w["slots"])]
        level_next[kl] = start + w["slots"]
        if w["slots"] == 1:
            merges_kind = "보통 칸(빈 단으로 옮김)"
        else:
            merges_kind = f"칸 {w['slots']}개 합침"
        merges.append({"code": head, "dims": cell(g, w["slots"]), "absorbed": absorbed, "sku": w["sku"], "kind": merges_kind})
        moves.append((w, head))

    # MIGRATION 의 작은 상품 → 같은 온도대 빈 보통 칸(합친 칸·쓰는 단과 겹치지 않게)
    taken = set(occupied) | {m["code"] for m in merges} | {a for m in merges for a in m["absorbed"]}
    for kl in used_levels:
        (t, area, rack), lv = kl
        taken |= {c for c in want if c.startswith(f"{t}-{area}-{rack}-{lv}-")}
    for sku, t, qty, item, lines in small:
        dest = None
        for g in NORMAL_GROUPS[t]:
            if capacity(item, cell(g)) < qty:
                continue
            for lv in ("3", "2", "4", "1", "5", "6"):  # 보통 칸은 허리 높이 3단부터
                dest = next((c for c in sorted(want) if c[0] == t and c.split("-")[1][0] == g and c not in taken
                             and c.split("-")[3] == lv and f"{c[0]}-{c.split('-')[1]}-{c.split('-')[2]}" not in SKIP_RACKS), None)
                if dest:
                    break
            if dest:
                break
        if not dest:
            unplaced.append((sku, "빈 보통 칸 없음"))
            continue
        taken.add(dest)
        moves.append(({"sku": sku, "temp": t, "qty": qty, "from": "MIGRATION", "lines": lines, "group": dest.split("-")[1][0]}, dest))
    return wide, small, merges, moves, unplaced, want


def main(apply):
    wide, small, merges, moves, unplaced, want = build()
    print(f"넓힐 상품 {len(wide)}(옆 {sum(1 for w in wide if w['slots'])}·위아래 {sum(1 for w in wide if w['levels'])}), MIGRATION 작은 상품 {len(small)}")
    kinds = collections.Counter((m["code"].split("-")[1][0], m["kind"]) for m in merges)
    for (g, k), n in sorted(kinds.items()):
        print(f"  그룹 {g} {k}: {n}")
    print(f"합친 칸 {len(merges)}, 닫을 칸 {sum(len(m['absorbed']) for m in merges)}, 옮길 상품 {len(moves)}, 못 넣음 {len(unplaced)}")
    racks_used = sorted({'-'.join(m['code'].split('-')[:3]) for m in merges})
    print(f"쓰는 랙 {len(racks_used)}: {', '.join(racks_used)}")
    for u in unplaced:
        print("  못 넣음", u)
    for w, dest in moves[:8]:
        print(f"  예: {w['sku']} {w['from']} → {dest}")
    if not apply:
        return
    s = v1.Session()
    cur = v1.current_locations(s)
    # 1) 합친 칸 치수 · 2) 합쳐진 칸 닫기
    ok = bad = 0
    for m in merges:
        x = cur[m["code"]]
        body = v1.keep_body(x, x.get("position"))
        body["dimensions"] = {"widthMm": m["dims"][0], "depthMm": m["dims"][1], "heightMm": m["dims"][2], "slotCount": 1}
        st, b = v1.http("PUT", f"{v1.GW}/locations/{x['locationId']}", s.sup(), body, version=x["version"])
        if st != 200:
            bad += 1
            print("  치수 실패", m["code"], st, b.get("code"), (b.get("detail") or "")[:100])
            continue
        for a in m["absorbed"]:
            y = cur.get(a)
            if not y or y.get("state") != "ACTIVE":
                continue
            st, b = v1.http("POST", f"{v1.GW}/locations/{y['locationId']}/state-changes", s.sup(),
                            {"targetState": "CLOSED", "reason": f"넓은 칸 {m['code']} 에 합침(layout-v3-wide) 2026-11-18"})
            if st != 202:
                bad += 1
                print("  닫기 실패", a, st, b.get("code"))
            time.sleep(0.03)
        ok += 1
    print(f"합친 칸 {ok}, 실패 {bad}")
    # 3) 재고 옮기기
    moved = failed = 0
    for w, dest in moves:
        body = {"reason": "칸에 안 들어가는 상품을 넓은 칸·빈 칸으로(layout-v3-wide) 2026-11-18", "approvalRef": APPROVAL_REF,
                "lines": [{"sku": w["sku"], "lotId": r["lotId"], "fromLocation": r["locationCode"], "qualityState": r["qualityState"],
                           "workState": r["workState"], "qty": r["qty"], "toLocation": dest} for r in w["lines"]]}
        st, b = v1.http("POST", f"{v1.GW}/mfcs/{v1.MFC}/stock-corrections", s.sup(), body)
        if st in (200, 201):
            moved += 1
        else:
            failed += 1
            print("  옮기기 실패", w["sku"], "→", dest, st, b.get("code"), (b.get("detail") or "")[:120])
        time.sleep(0.06)
    print(f"옮김 {moved}, 실패 {failed}")
    # 4) 계획 남기기 — mfc_layout_v2.plan() 이 읽어 합친 칸을 되돌리지 않는다
    json.dump({"approvalRef": APPROVAL_REF, "date": "2026-11-18",
               "merged": {m["code"]: {"widthMm": m["dims"][0], "depthMm": m["dims"][1], "heightMm": m["dims"][2], "sku": m["sku"], "kind": m["kind"]} for m in merges},
               "closed": sorted({a for m in merges for a in m["absorbed"]})},
              open(PLAN_FILE, "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=1)
    print("계획 저장", os.path.basename(PLAN_FILE))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main("--apply" in sys.argv)
