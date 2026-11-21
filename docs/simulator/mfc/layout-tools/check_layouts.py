"""세 센터(부산 진구점·세종·창원) 평면을 검사한다 — 2026-11-21.

  1. README 의 '수량 요약' 표 숫자 == layout.json 에서 센 숫자(랙·칸·팔레트·패킹대·출하대기 랙·하차 칸)
  2. 랙끼리·랙과 장애물(팔레트·작업대)·격리 구역이 겹치지 않음, 랙이 같은 온도 구역 사각형 안에 있음
  3. 모든 랙 앞(작업자 서는 쪽)에 0.7 m 통로가 비어 있음(다른 랙·팔레트가 막지 않음), 건물 밖으로 나가지 않음
  4. 백엔드 칸 코드(Q-03 문법, deploy/lan/seed/mfc_layout.py 와 같은 변환)가 형식에 맞고 유일
  5. (참고만) 85 % 칸 수 vs 부산 SKU — 2026-11-21 사용자 결정으로 실패로 보지 않는다(다품종 소량 적재, 랙은 면적에 비례)
실행: python -I docs/simulator/mfc/layout-tools/check_layouts.py   (실패하면 종료 코드 1)
"""
import collections
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MFC_DIR = os.path.dirname(HERE)
CENTERS = (("부산 진구점", "layout"), ("세종", "layout-sejong"), ("창원", "layout-changwon"))
RW, RD, AISLE = 1.25, 0.45, 0.70
SKU = {"FROZEN": 2180, "CHILLED": 3270, "AMBIENT": 5450}
SKU_G = {"C": 1050, "D": 1100, "E": 800, "F": 1100, "G": 1050, "H": 350}
TEMP_LETTER = {"FROZEN": "F", "CHILLED": "C", "AMBIENT": "A"}
SHELF_RE = re.compile(r"^[ACF]-[A-Z][1-9]-\d{2}-[1-9]-\d{2}$")
EPS = 1e-6


def fp(r):
    lx, lz = (RW, RD) if r["along"] == "x" else (RD, RW)
    return {"x0": r["cx"] - lx / 2, "z0": r["cz"] - lz / 2, "x1": r["cx"] + lx / 2, "z1": r["cz"] + lz / 2}


def hit(a, b):
    return a["x0"] < b["x1"] - EPS and b["x0"] < a["x1"] - EPS and a["z0"] < b["z1"] - EPS and b["z0"] < a["z1"] - EPS


def inside(a, b):
    return a["x0"] >= b["x0"] - EPS and a["x1"] <= b["x1"] + EPS and a["z0"] >= b["z0"] - EPS and a["z1"] <= b["z1"] + EPS


def counts(L):
    c = collections.OrderedDict()
    racks = L["racks"]
    for t, name in (("FROZEN", "냉동"), ("CHILLED", "냉장"), ("AMBIENT", "상온")):
        rs = [r for r in racks if r["temp"] == t]
        c[f"{name} 랙"] = len(rs)
        c[f"{name} 칸"] = sum(r["levels"] * r["slotsPerLevel"] for r in rs)
    for g in "CDEFGH":
        c[f"상온 {g} 랙"] = sum(1 for r in racks if r["temp"] == "AMBIENT" and r["group"][0] == g)
    c["랙 합계"] = len(racks)
    c["선반 칸 합계"] = L["locationCount"]
    for t, name in (("FROZEN", "냉동"), ("CHILLED", "냉장"), ("AMBIENT", "상온")):
        c[f"보충재고 팔레트 {name}"] = sum(1 for r in L["reserves"] if r["temp"] == t)
    c["패킹대"] = len(L["stations"])
    c["외부 출하대기 랙"] = sum(1 for o in L["obstacles"] if o["kind"] == "SHIP_RACK")
    c["하차 적치 칸"] = len(L["dockSlots"])
    return c


def readme_table(path):
    out = {}
    if not os.path.exists(path):
        return None
    txt = open(path, encoding="utf-8").read()
    sec = txt.split("## 수량 요약", 1)
    if len(sec) < 2:
        return None
    for line in sec[1].splitlines()[1:]:
        if line.startswith("## "):
            break
        m = re.match(r"^\|\s*([^|]+?)\s*\|\s*([\d,]+)\s*\|", line)
        if m:
            out[m.group(1)] = int(m.group(2).replace(",", ""))
    return out


def check(name, sub):
    errs = []
    L = json.load(open(os.path.join(MFC_DIR, sub, "layout.json"), encoding="utf-8"))
    racks = L["racks"]
    fps = [fp(r) for r in racks]
    bld = {"x0": 0, "z0": 0, "x1": L["width"], "z1": L["buildingDepth"]}
    zones = {z["id"]: z for z in L["zones"]}
    temp_zone = collections.defaultdict(list)
    for z in L["zones"]:
        if z["kind"] in ("FROZEN", "CHILLED", "AMBIENT"):
            temp_zone[z["kind"]].append(z["rect"])
    quarantine = [z["rect"] for z in L["zones"] if z["kind"] == "QUARANTINE"]
    blocks = [o["rect"] for o in L["obstacles"] if o["kind"] in ("RESERVE", "TABLE", "MATERIAL", "PALLET")]
    # 2. 겹침·구역
    for i, a in enumerate(fps):
        if not inside(a, bld):
            errs.append(f"{racks[i]['id']} 건물 밖")
        if not any(inside(a, zr) for zr in temp_zone[racks[i]["temp"]]):
            errs.append(f"{racks[i]['id']} 같은 온도 구역 밖")
        for j in range(i + 1, len(fps)):
            if hit(a, fps[j]):
                errs.append(f"{racks[i]['id']}↔{racks[j]['id']} 겹침")
        for q in quarantine + blocks:
            if hit(a, q):
                errs.append(f"{racks[i]['id']} 장애물/격리와 겹침")
    # 3. 앞 통로 0.7 m
    for r, a in zip(racks, fps):
        if r["along"] == "z":
            s = {"x0": a["x1"], "x1": a["x1"] + AISLE} if r["face"] > 0 else {"x0": a["x0"] - AISLE, "x1": a["x0"]}
            s.update({"z0": a["z0"], "z1": a["z1"]})
        else:
            s = {"z0": a["z1"], "z1": a["z1"] + AISLE} if r["face"] > 0 else {"z0": a["z0"] - AISLE, "z1": a["z0"]}
            s.update({"x0": a["x0"], "x1": a["x1"]})
        if not inside(s, bld) or any(hit(s, b) for b in fps + blocks):
            errs.append(f"{r['id']} 앞 0.7 m 통로 막힘")
    # 4. 코드
    rk = {r["id"]: r for r in racks}
    codes = set()
    for loc in L["locations"]:
        r = rk[loc["rackId"]]
        code = f"{TEMP_LETTER[loc['temp']]}-{r['group'][0]}{int(r['group'][1:])}-{r['rackNo']:02d}-{loc['level']}-{loc['slot']:02d}"
        if not SHELF_RE.match(code):
            errs.append(f"Q-03 형식 아님 {code}")
        codes.add(code)
    if len(codes) != len(L["locations"]):
        errs.append("칸 코드 중복")
    for t in ("FROZEN", "CHILLED", "AMBIENT"):
        n = sum(1 for x in L["reserves"] if x["temp"] == t)
        if n > 99:
            errs.append(f"팔레트 {t} {n} > 99 (R1-NN 2자리)")
    for zid in ("AQ", "BQ", "CQ", "RCV"):
        if zid not in zones:
            errs.append(f"mfc_layout.py 가 찾는 구역 {zid} 없음")
    # 5. 수용량
    c = counts(L)
    # 5. SKU: 2026-11-21 사용자 "다품종 소량 적재 — 평수에 맞게 랙 갯수를 늘리고 줄이는 것뿐" → 실패가 아니라 참고(칸 하나 = SKU 여러 개)
    notes = [f"{tn} 85% 칸 {c[f'{tn} 칸'] * 0.85:.0f} / SKU {SKU[t]}" for t, tn in (("FROZEN", "냉동"), ("CHILLED", "냉장"), ("AMBIENT", "상온"))]
    print(f"[{name}] 참고(실패 아님): " + ", ".join(notes))
    # 1. README
    t = readme_table(os.path.join(MFC_DIR, sub, "README.md"))
    if t is None:
        errs.append("README 에 '## 수량 요약' 표 없음")
    else:
        for k, v in c.items():
            if k not in t:
                errs.append(f"README 표에 '{k}' 없음")
            elif t[k] != v:
                errs.append(f"README {k} = {t[k]} ≠ layout.json {v}")
    print(f"[{name}] " + ", ".join(f"{k} {v:,}" for k, v in c.items()))
    print(f"[{name}] {'통과' if not errs else '실패 ' + str(len(errs))}")
    for e in errs[:30]:
        print("   -", e)
    return not errs


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    ok = all([check(n, s) for n, s in CENTERS])
    sys.exit(0 if ok else 1)
