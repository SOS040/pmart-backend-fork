"""LAN 시험 센터 칸 구조를 MFC 업무 문서 설계로 바꾼다(2026-11-16, 사용자 "칸구조 A로 할거고").

문서(MFC-business-logic 1장 3-3·4-2): 상온 C 6단×9칸·D 6단×10칸·E 6단×8칸·F 6단×10칸·G 6단×9칸·H 5단×5칸(큰 상품),
냉장(B) 5단×7칸, 냉동(A) 5단×6칸. 랙 수·위치(도면 layout.json)는 그대로 — 칸만 나눈다. 합계 선반 칸 14,559(+ 고회전 32 그대로).

하는 일(모두 API — DB 직접 입력 없음):
  1) 지금 칸(01~04 등)의 치수를 새 칸 수에 맞춰 바꾼다(PUT — 폭 = 랙 길이 1.25 m 의 90 % ÷ 칸 수, 높이 = 2.1 m ÷ 단 수).
  2) 새 칸(예 05~10)을 등록한다(피킹 좌표 = 같은 랙 좌표).
  3) H 의 6단 칸(5단으로 줄어듦)은 재고가 없을 때만 닫는다.
사용: python -I deploy/lan/seed/mfc_layout_v2.py           # 계획만
      python -I deploy/lan/seed/mfc_layout_v2.py --apply   # 적용
"""
import json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402  (http·Session·current_locations·snapshot·keep_body·GW·MFC·LAYOUT)

RACK_LEN, RACK_DEPTH_MM, RACK_HEIGHT = 1.25, 450, 2.1
# 그룹 첫 글자 → (단, 칸)
DESIGN = {"A": (5, 6), "B": (5, 7), "C": (6, 9), "D": (6, 10), "E": (6, 8), "F": (6, 10), "G": (6, 9), "H": (5, 5)}
TEMP_LETTER = {"FROZEN": "F", "CHILLED": "C", "AMBIENT": "A"}


def plan():
    L = json.load(open(v1.LAYOUT, encoding="utf-8"))
    pick = {}
    for loc in L["locations"]:
        pick.setdefault(loc["rackId"], (loc["pickX"], loc["pickZ"]))
    want = {}
    for r in L["racks"]:
        levels, slots = DESIGN[r["group"][0]]
        t = TEMP_LETTER[r["temp"]]
        area = r["group"][0] + str(int(r["group"][1:]))
        dims = {"widthMm": round(RACK_LEN * 900 / slots), "depthMm": RACK_DEPTH_MM,
                "heightMm": round(RACK_HEIGHT * 1000 / levels), "slotCount": 1}
        px, pz = pick[r["id"]]
        for lv in range(1, levels + 1):
            for sl in range(1, slots + 1):
                code = f"{t}-{area}-{r['rackNo']:02d}-{lv}-{sl:02d}"
                want[code] = {"locationCode": code, "zone": "STORAGE", "temperatureZone": r["temp"],
                              "position": {"xM": px, "zM": pz}, "dimensions": dims}
    return want


def is_rack_cell(code):
    p = code.split("-")
    return len(p) == 5 and p[1][0] in DESIGN and p[1] != "R1"


def main(apply):
    s = v1.Session()
    want = plan()
    cur = v1.current_locations(s)
    active = {c: x for c, x in cur.items() if x.get("state") == "ACTIVE" and x.get("zone") == "STORAGE"}
    resize = [c for c in want if c in active and (active[c].get("dimensions") or {}) .get("widthMm") != want[c]["dimensions"]["widthMm"]
              or (c in active and (active[c].get("dimensions") or {}).get("heightMm") != want[c]["dimensions"]["heightMm"])]
    new = [c for c in want if c not in cur]
    reopen = [c for c in want if c in cur and cur[c].get("state") != "ACTIVE"]
    drop = [c for c in active if is_rack_cell(c) and c not in want]
    by_t = {}
    for c in want:
        by_t[c[0]] = by_t.get(c[0], 0) + 1
    print(f"설계 칸 {len(want)} (상온 {by_t.get('A', 0)}·냉장 {by_t.get('C', 0)}·냉동 {by_t.get('F', 0)})")
    print(f"치수 바꿈 {len(resize)}, 새로 등록 {len(new)}, 닫힌 칸 다시 열기 필요 {len(reopen)}, 닫을 칸(H 6단 등) {len(drop)}")
    if reopen:
        print("  ※ 닫힌 칸과 코드가 겹침 — 손으로 확인:", reopen[:5])
    if not apply:
        return
    done = failed = 0
    for i, c in enumerate(resize):
        x = active[c]
        b = v1.keep_body(x, x.get("position"))
        b["dimensions"] = want[c]["dimensions"]
        st, bb = v1.http("PUT", f"{v1.GW}/locations/{x['locationId']}", s.sup(), b, version=x["version"])
        done += st == 200
        if st != 200:
            failed += 1
            if failed <= 5:
                print("  치수 실패", c, st, bb.get("code"), bb.get("detail"))
        if (i + 1) % 1000 == 0:
            print(f"  치수 {i + 1}/{len(resize)}", flush=True)
        time.sleep(0.05)
    print(f"치수: 바꿈 {done}, 실패 {failed}")
    made = failed = 0
    for i, c in enumerate(new):
        b = dict(want[c]); b["mfcId"] = v1.MFC; b["highTurnover"] = False
        st, bb = v1.http("POST", f"{v1.GW}/locations", s.sup(), b)
        made += st == 201
        if st != 201 and not (st == 409 and bb.get("code") == "LOCATION_CODE_DUPLICATE"):
            failed += 1
            if failed <= 5:
                print("  등록 실패", c, st, bb.get("code"), bb.get("detail"))
        if (i + 1) % 1000 == 0:
            print(f"  등록 {i + 1}/{len(new)}", flush=True)
        time.sleep(0.05)
    print(f"등록: 새로 {made}, 실패 {failed}")
    left = {r["locationCode"] for r in v1.snapshot() if r["qty"] > 0}
    closed = kept = 0
    for c in drop:
        if c in left:
            kept += 1
            print("  재고가 있어 열어 둠:", c)
            continue
        st, bb = v1.http("POST", f"{v1.GW}/locations/{active[c]['locationId']}/state-changes", s.sup(),
                         {"targetState": "CLOSED", "reason": "MFC 문서 칸 설계로 변경(H 5단) 2026-11-16"})
        closed += st == 202
        if st != 202:
            print("  닫기 실패", c, st, bb.get("code"), bb.get("detail"))
    print(f"닫음 {closed}, 열어 둠 {kept}")


if __name__ == "__main__":
    main("--apply" in sys.argv)
