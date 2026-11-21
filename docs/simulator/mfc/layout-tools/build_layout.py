"""세종·창원 MFC 평면(layout.json)을 기하 배치 규칙으로 만든다 — 2026-11-21.

부산 진구점 layout.json 과 같은 스키마(시뮬레이터 layout.ts 출력 형식)라서
  - tools/draw.py 로 그림을 그리고
  - deploy/lan/seed/mfc_layout.py 를 PMART_LAYOUT=<경로> 로 돌려 백엔드 칸 코드(Q-03 문법)를 계획할 수 있다.

랙 수는 비례 축소가 아니라 아래 규칙으로 직사각형 안에 실제로 놓아서 나온 값이다(모든 랙 좌표가 layout.json 에 있다).
  랙        1.25(폭) × 0.45(깊이) m, 높이 2.1 m           MFC 문서 1장 1절·9-1
  랙 사이 통로  0.70 m                                       1장 1-2·3-1 "70cm 유지"
  줄 형태    벽 붙은 홑줄(S) + 등 맞댄 겹줄(D) — 모든 줄이 0.7 m 통로 하나에 면함
  줄 끝 여유  북쪽(안쪽 벽) 0.8~1.0 m, 문 쪽 1.2~1.3 m       [가정] 끝을 돌아 나갈 수 있게(막다른 통로 없음)
  횡단 통로  줄이 12랙(15 m)을 넘으면 가운데 1.2 m           [가정] 부산 평면 냉동·냉장 줄(18랙)에 가운데 횡단 통로가 있음
  팔레트     1.1 × 1.1 m, 간격 0.2 m, 접근 통로 1.2~1.5 m    1장 5-1 [가정]
  패킹 모듈  2.0 × 2.2 m(상판 1.6 × 0.8 m)                  1장 7-3·7-5
  공용 주 통로 1.5 m 이상                                    1장 1-2 "1.5m 개념 폭"
칸 구조(단 × 칸)는 부산과 같다(MFC 문서 3-3·4-2): 냉동 5×6, 냉장 5×7, 상온 C·G 6×9, D·F 6×10, E 6×8, H 5×5.

실행: python -I docs/simulator/mfc/layout-tools/build_layout.py   (두 센터의 layout.json 을 다시 쓴다)
"""
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
MFC_DIR = os.path.dirname(HERE)
PY = 3.305785  # ㎡ / 평

RW, RD, AISLE = 1.25, 0.45, 0.70
CROSS, MAX_RUN = 1.2, 12
PAL, PAL_GAP = 1.1, 0.2
STRUCT = {  # 그룹 글자 → (온도, 단, 칸) — MFC 문서 3-3·4-2
    "A": ("FROZEN", 5, 6), "B": ("CHILLED", 5, 7),
    "C": ("AMBIENT", 6, 9), "D": ("AMBIENT", 6, 10), "E": ("AMBIENT", 6, 8),
    "F": ("AMBIENT", 6, 10), "G": ("AMBIENT", 6, 9), "H": ("AMBIENT", 5, 5)}
# 부산과 같은 SKU 구성(MFC 문서 2장·3-2) — 사용자 결정 2026-11-21
SKU = {"A": 2180, "B": 3270, "C": 1050, "D": 1100, "E": 800, "F": 1100, "G": 1050, "H": 350}
OPS = 0.85  # 85 % 운영 상한(3-3·4-2)


def R(x0, z0, x1, z1):
    return {"x0": round(x0, 3), "z0": round(z0, 3), "x1": round(x1, 3), "z1": round(z1, 3)}


def area(r):
    return (r["x1"] - r["x0"]) * (r["z1"] - r["z0"])


# ---------- 기하 규칙 ----------
def across(pattern):
    """줄 형태 문자열 → [(중심 오프셋, 면 방향)], 전체 폭.
    S  = 벽 붙은 홑줄(처음이면 +쪽, 끝이면 -쪽 통로를 봄), D = 0.7 m 통로 + 겹줄, A = 마지막 0.7 m 통로,
    끝 S 는 앞에 0.7 m 통로를 둔다. 모든 줄이 통로 하나에 면하는지 assert."""
    rows, pos = [], 0.0
    toks = list(pattern)
    for i, t in enumerate(toks):
        if t == "S" and i == 0:
            rows.append((pos + RD / 2, +1)); pos += RD
        elif t == "D":
            pos += AISLE
            rows.append((pos + RD / 2, -1)); rows.append((pos + RD * 1.5, +1)); pos += 2 * RD
        elif t == "A":
            pos += AISLE
        elif t == "S":
            pos += AISLE
            rows.append((pos + RD / 2, -1)); pos += RD
        else:
            raise ValueError(t)
    # 마지막 줄이 +쪽을 보면 뒤에 통로가 있어야 한다
    assert not (rows[-1][1] == +1 and toks[-1] == "D"), f"마지막 겹줄 뒤 통로 없음: {pattern}"
    return rows, pos


def max_rows(width):
    """폭 안에 넣을 수 있는 최대 줄 수(S + D… + 끝 S 또는 A) — 검증용."""
    best = 0
    for nd in range(0, 40):
        for end, w_end, r_end in (("A", AISLE, 0), ("S", AISLE + RD, 1)):
            w = RD + nd * (AISLE + 2 * RD) + w_end
            if w <= width + 1e-9:
                best = max(best, 1 + 2 * nd + r_end)
    return best


def run_len(n):
    """한 줄에 랙 n 개를 놓을 때 길이(횡단 통로 포함)."""
    segs = math.ceil(n / MAX_RUN) if n > MAX_RUN else 1
    if n > MAX_RUN:
        segs = max(2, segs)
    return n * RW + (segs - 1) * CROSS, segs


def max_run(length):
    n = 0
    while run_len(n + 1)[0] <= length + 1e-9:
        n += 1
    return n


def run_positions(start, n):
    """줄 방향 랙 중심 좌표(횡단 통로 포함, 두 토막은 앞쪽이 큼)."""
    total, segs = run_len(n)
    sizes = [n // segs + (1 if i < n % segs else 0) for i in range(segs)]
    out, pos = [], start
    for si, k in enumerate(sizes):
        for _ in range(k):
            out.append(pos + RW / 2); pos += RW
        if si < segs - 1:
            pos += CROSS
    return out


def pallet_grid(rect, aisle, axis="z", cols=None, skip=()):
    """팔레트 자리: 접근 통로(aisle)에 면한 두 열(P|통로|P …). axis = 열 방향. 반환 [(rect, stand)]."""
    out = []
    x0, x1 = (rect["x0"], rect["x1"]) if axis == "z" else (rect["z0"], rect["z1"])
    a0, a1 = (rect["z0"], rect["z1"]) if axis == "z" else (rect["x0"], rect["x1"])
    # 가로 방향 패턴: P, aisle, P, (gap 0.2), P, aisle, P …
    pos, col_list = x0, []
    while True:
        if pos + PAL > x1 + 1e-9:
            break
        c1 = pos
        if pos + PAL + aisle + PAL <= x1 + 1e-9:
            col_list.append((c1, c1 + PAL + aisle / 2))
            col_list.append((c1 + PAL + aisle, c1 + PAL + aisle / 2))
            pos = c1 + 2 * PAL + aisle + PAL_GAP
        else:
            col_list.append((c1, c1 + PAL + min(aisle, x1 - c1 - PAL) / 2))
            break
    if cols:
        col_list = col_list[:cols]
    n_along = int((a1 - a0 + PAL_GAP) // (PAL + PAL_GAP + 0.2))
    for c, stand_c in col_list:
        for k in range(n_along):
            s = a0 + k * (PAL + PAL_GAP + 0.2)
            rr = R(c, s, c + PAL, s + PAL) if axis == "z" else R(s, c, s + PAL, c + PAL)
            if any(not (rr["x1"] <= q["x0"] or q["x1"] <= rr["x0"] or rr["z1"] <= q["z0"] or q["z1"] <= rr["z0"]) for q in skip):
                continue
            st = {"x": round(stand_c, 3), "z": round(s + PAL / 2, 3)} if axis == "z" else {"x": round(s + PAL / 2, 3), "z": round(stand_c, 3)}
            out.append((rr, st))
    return out


# ---------- 공통 빌더 ----------
class Builder:
    def __init__(self, width, bdepth, yard):
        self.L = {"width": width, "depth": bdepth + yard, "buildingDepth": bdepth, "zones": [], "walls": [],
                  "racks": [], "locationCount": 0, "locations": [], "sampleLocations": [], "obstacles": [],
                  "stations": [], "shippingSlots": [], "reserves": [], "dockSlots": [], "points": {}}
        self.rack_seq = {"A": 0, "B": 0, "C": 0}

    def zone(self, zid, kind, label, rect, doc=None):
        z = {"id": zid, "kind": kind, "label": label, "rect": rect}
        if doc is not None:
            z["docPyeong"] = doc
        self.L["zones"].append(z)
        return z

    def wall(self, x0, z0, x1, z1, glass=False, height=3):
        w = {"rect": R(x0, z0, x1, z1), "height": height}
        if height >= 2:
            w["glass"] = glass
        self.L["walls"].append(w)

    def box_walls(self, r, doors=(), glass=False, t=0.1):
        """사각형 둘레 벽. doors = [(변 'N'/'S'/'W'/'E', 시작, 끝)] — 그 구간을 비운다."""
        sides = {"N": ("x", r["z0"], r["x0"], r["x1"]), "S": ("x", r["z1"], r["x0"], r["x1"]),
                 "W": ("z", r["x0"], r["z0"], r["z1"]), "E": ("z", r["x1"], r["z0"], r["z1"])}
        for s, (ax, c, a0, a1) in sides.items():
            cuts = sorted((d[1], d[2]) for d in doors if d[0] == s)
            pos = a0
            for c0, c1 in cuts + [(a1, a1)]:
                if c0 - pos > 1e-6:
                    if ax == "x":
                        self.wall(pos, c - t, c0, c + t, glass)
                    else:
                        self.wall(c - t, pos, c + t, c0, glass)
                pos = max(pos, c1)

    def rack_block(self, temp, origin_across, origin_along, pattern, n_along, along="z", groups=None):
        """랙 줄 블록. 줄은 along 방향, 줄 사이는 across 방향. 줄 번호(line) = 통로 번호(통로 하나 = 줄 하나).
        groups: [(그룹 글자, 랙 수)] — 순서대로 채운다(상온 C~H). 반환 = 놓은 랙 수, 최대 수용 랙 수."""
        rows, width = across(pattern)
        pos_along = run_positions(origin_along, n_along)
        # 통로 번호: 면 방향과 위치로 통로를 구분 — 같은 통로를 보는 두 줄이 한 줄 번호
        aisle_of = []
        aid = 0
        prev = None
        for off, face in rows:
            key = (off + face * (RD / 2 + AISLE / 2))
            key = round(key, 3)
            if prev is None or abs(key - prev) > 1e-6:
                aid += 1; prev = key
            aisle_of.append(aid)
        slots = []
        for (off, face), aid in zip(rows, aisle_of):
            for p in pos_along:
                slots.append((aid, off, face, p))
        if groups is None:
            groups = [({"FROZEN": "A", "CHILLED": "B"}[temp], len(slots))]
        assert sum(n for _, n in groups) <= len(slots), (temp, groups, len(slots))
        line_no, rack_no, last_aisle = {}, {}, {}
        i = 0
        for g, n in groups:
            for _ in range(n):
                aid, off, face, p = slots[i]; i += 1
                if last_aisle.get(g) != aid:
                    line_no[g] = line_no.get(g, 0) + 1; rack_no[g] = 0; last_aisle[g] = aid
                rack_no[g] += 1
                assert line_no[g] <= 9 and rack_no[g] <= 99, "Q-03: 줄 1자리·랙 2자리"
                rid_letter = {"FROZEN": "A", "CHILLED": "B", "AMBIENT": "C"}[temp]
                self.rack_seq[rid_letter] += 1
                if along == "z":
                    cx, cz = origin_across + off, p
                else:
                    cx, cz = p, origin_across + off
                t, lv, sl = STRUCT[g]
                assert t == temp
                self.L["racks"].append({"id": f"R{rid_letter}-{self.rack_seq[rid_letter]}", "temp": temp,
                                        "group": f"{g}{line_no[g]:02d}", "rackNo": rack_no[g],
                                        "cx": round(cx, 4), "cz": round(cz, 4), "along": along, "face": face,
                                        "levels": lv, "slotsPerLevel": sl})
        return i, len(slots), width

    def reserves(self, temp, prefix, items):
        n = sum(1 for r in self.L["reserves"] if r["temp"] == temp)
        for rr, st in items:
            n += 1
            self.L["reserves"].append({"id": f"{prefix}-{n}", "temp": temp, "rect": rr, "stand": st})
            self.L["obstacles"].append({"rect": rr, "height": 0.15, "kind": "RESERVE"})

    def stations(self, x0, z_north_table, z_south_table, n_per_row, pitch=2.0):
        k = 0
        for row, zt, dz in ((0, z_north_table, 1.2), (1, z_south_table, -1.2)):
            for j in range(n_per_row):
                k += 1
                cx = x0 + 0.9 + j * pitch
                self.L["stations"].append({"id": f"PS-{k}", "index": k - 1, "table": {"x": round(cx, 3), "z": zt},
                                           "stand": {"x": round(cx, 3), "z": round(zt + dz, 3)}})
                self.L["obstacles"].append({"rect": R(cx - 0.8, zt - 0.4, cx + 0.8, zt + 0.4), "height": 0.9, "kind": "TABLE"})

    def ship_racks(self, xs, z):
        for ri, x in enumerate(xs):
            r = R(x - 0.625, z - 0.225, x + 0.625, z + 0.225)
            self.L["obstacles"].append({"rect": r, "height": 2.1, "kind": "SHIP_RACK"})
            for lv in range(1, 5):
                for s in range(1, 5):
                    self.L["shippingSlots"].append({"id": f"SR{ri + 1}-{lv}{s}", "rackIndex": ri,
                                                    "pos": {"x": round(r["x0"] + 0.175 + 0.3 * (s - 1), 3), "y": round(0.25 + 0.5 * (lv - 1), 2), "z": z}})

    def docks(self, x0, z0, nx, nz, pitch=0.75):
        k = 0
        for j in range(nz):
            for i in range(nx):
                k += 1
                self.L["dockSlots"].append({"id": f"DK-{k}", "pos": {"x": round(x0 + i * pitch, 3), "z": round(z0 + j * pitch, 3)}})

    def finish(self):
        locs = []
        for r in self.L["racks"]:
            px = r["cx"] + (r["face"] * (RD / 2 + AISLE / 2) if r["along"] == "z" else 0)
            pz = r["cz"] + (r["face"] * (RD / 2 + AISLE / 2) if r["along"] == "x" else 0)
            for lv in range(1, r["levels"] + 1):
                for s in range(1, r["slotsPerLevel"] + 1):
                    locs.append({"code": f"{r['group']}-{r['rackNo']:02d}-{lv}{s:02d}", "rackId": r["id"], "temp": r["temp"],
                                 "level": lv, "slot": s, "pickX": round(px, 2), "pickZ": round(pz, 2)})
        self.L["locations"] = locs
        self.L["locationCount"] = len(locs)
        self.L["sampleLocations"] = [l["code"] for l in locs[:3]]
        codes = [l["code"] for l in locs]
        assert len(codes) == len(set(codes))
        return self.L


def allocate_ambient(total):
    """상온 랙 total 개를 C~H 에 나눈다: 먼저 그룹별 최소(85 % 슬롯 ≥ SKU), 남는 랙은 여유율이 가장 작은 그룹에 하나씩."""
    alloc = {}
    for g in "CDEFGH":
        _, lv, sl = STRUCT[g]
        alloc[g] = math.ceil(SKU[g] / (lv * sl * OPS))
    assert sum(alloc.values()) <= total, ("상온 랙 부족", sum(alloc.values()), total)
    for _ in range(total - sum(alloc.values())):
        def margin(g):
            _, lv, sl = STRUCT[g]
            return (alloc[g] * lv * sl * OPS - SKU[g]) / SKU[g]
        g = min("CDEFGH", key=margin)
        alloc[g] += 1
    return alloc


# ---------- 세종: 3층 한 층 812평, 부산 진구점 배치를 따르되 3층 조건(화물 승강기 입고·리프트 출하) ----------
# 2026-11-21 재설계(사용자 "그 설계도가 … 다시"): 첫 안은 빈 "예비" 방·떨어진 보충재고실·좁은 피킹실로 부산과 모양이 달랐다.
# 이번 안: 부산과 같은 방 구성·같은 랙 수(냉동 105·냉장 125·상온 138, 상온은 가로 줄 C~H) — SKU 가 같으니 랙도 같다.
# 넓어진 면적(463 → 812평)은 빈 방이 아니라 저온 보충재고(방 안 팔레트), 입고창고, 패킹 12대, 넓은 작업 바닥으로 쓴다.
BUSAN_AMB = {"C": 24, "D": 24, "E": 21, "F": 24, "G": 25, "H": 20}  # 부산 진구점 상온 배분(138)


def build_sejong():
    W, BD = 64.0, 42.0          # 64 × 42 = 2,688 ㎡ = 813.1평 [가정 — 3층 한 층 812평 직사각형]
    b = Builder(W, BD, 8.0)
    END_N = 1.0
    COLD_Z1 = 39.4               # 저온실 남쪽 벽(아래 2.6 m 는 복도 — 저온실 문)
    # 냉동 A: 6줄(겹줄 3) × 18랙 = 108자리 중 105(부산과 같음), 방 가운데
    rA = R(0, 0, 16.0, COLD_Z1)
    pat_a = "DDDA"
    _, wA = across(pat_a)
    xa = round((rA["x1"] - wA) / 2, 2)
    nA, capA, _ = b.rack_block("FROZEN", xa, END_N, pat_a, 18, groups=[("A", 105)])
    # 냉장 B: 7줄(홑줄 + 겹줄 3) × 18랙 = 126자리 중 125
    rB = R(16.2, 0, 30.8, COLD_Z1)
    pat_b = "SDDDA"
    _, wB = across(pat_b)
    xb = round(rB["x0"] + (rB["x1"] - rB["x0"] - wB) / 2, 2)
    nB, capB, _ = b.rack_block("CHILLED", xb, END_N, pat_b, 18, groups=[("B", 125)])
    # 상온 C~H: 가로 줄 16줄(겹줄 8) × 9랙 = 144자리 중 138, 위에서부터 C·D·E·F·G·H
    rC = R(31.0, 0, 49.4, 16.5)
    pat_c = "DDDDDDDDA"
    _, wC = across(pat_c)
    run_c, _ = run_len(9)
    xc = round(rC["x0"] + (rC["x1"] - rC["x0"] - run_c) / 2, 2)
    nC, capC, _ = b.rack_block("AMBIENT", 1.0, xc, pat_c, 9, along="x", groups=[(g, BUSAN_AMB[g]) for g in "CDEFGH"])
    alloc = dict(BUSAN_AMB)
    # 오른쪽 열: 입고창고 → 입고·검수(+ 화물 승강기) → 휴게·사무 (부산과 같은 순서)
    rWH = R(49.6, 0, W, 21.6)
    rRCV = R(49.6, 21.8, 58.6, 32.4)
    rELV = R(58.8, 21.8, W, 32.4)
    rOFF = R(49.6, 32.6, W, COLD_Z1)
    rPK = R(33.0, 21.6, 47.0, 30.6)
    rLIFT = R(36.0, 35.4, 44.0, COLD_Z1)
    rAQ = R(0.3, COLD_Z1 - 3.9, 3.9, COLD_Z1 - 0.3)
    rBQ = R(16.5, COLD_Z1 - 3.9, 20.1, COLD_Z1 - 0.3)
    rCQ = R(W - 3.9, 21.6 - 3.9, W - 0.3, 21.6 - 0.3)
    Z = b.zone
    Z("A", "FROZEN", "냉동 A", rA, None)
    Z("B", "CHILLED", "냉장 B", rB, None)
    Z("C-H", "AMBIENT", "상온 C~H", rC, None)
    Z("WH", "INBOUND_STORE", "입고창고", rWH, None)
    Z("RCV", "RECEIVING", "입고·검수", rRCV, None)
    Z("ELV", "LIFT", "화물 승강기 2대", rELV, None)
    Z("OFF", "OFFICE", "휴게·사무", rOFF, None)
    Z("PK", "PACKING", "패킹", rPK, None)
    Z("LIFT", "LIFT", "출하 리프트", rLIFT, None)
    Z("AQ", "QUARANTINE", "냉동 격리", rAQ, 4)
    Z("BQ", "QUARANTINE", "냉장 격리", rBQ, 4)
    Z("CQ", "QUARANTINE", "상온 격리", rCQ, 4)
    Z("SHIP", "SHIPPING", "1층 출하대기(리프트 아래)", R(36.0, 44.2, 44.0, 46.8), 3)
    Z("PARK", "PARKING", "라이더 대기(1층)", R(45.0, 44.6, 57.0, 48.0))
    bw = b.box_walls
    bw(R(0, 0, W, BD))
    bw(rA, doors=[("S", 6.0, 9.0)])
    bw(rB, doors=[("S", 22.0, 25.0), ("E", 27.0, 30.0)])
    bw(rC, doors=[("S", 32.0, 34.5), ("S", 45.5, 48.0)])
    bw(rWH, doors=[("W", 17.0, 20.0), ("S", 51.0, 54.0)])
    bw(rRCV, doors=[("W", 24.0, 28.0)])
    bw(rELV, doors=[("W", 22.6, 25.6), ("W", 28.0, 31.0)])
    bw(rOFF, doors=[("W", 34.5, 36.5)])
    bw(rPK, doors=[("N", 44.0, 46.5), ("S", 39.0, 41.5)], glass=True)
    bw(rLIFT, doors=[("N", 38.0, 42.0)])
    for r in (rAQ, rBQ):
        bw(r, doors=[("E", r["z0"] + 0.5, r["z0"] + 1.7)])
    bw(rCQ, doors=[("W", rCQ["z0"] + 0.5, rCQ["z0"] + 1.7)])
    # 보충재고 팔레트: 저온실은 랙 남쪽(부산처럼 방 안), 상온은 입고창고
    b.reserves("FROZEN", "AR", pallet_grid(R(4.4, 27.0, rA["x1"] - 0.4, COLD_Z1 - 0.4), 1.5, skip=[rAQ]))
    b.reserves("CHILLED", "BR", pallet_grid(R(20.6, 27.0, rB["x1"] - 0.4, COLD_Z1 - 0.4), 1.5, skip=[rBQ]))
    b.reserves("AMBIENT", "WR", pallet_grid(R(rWH["x0"] + 0.5, 0.6, rWH["x1"] - 0.4, rCQ["z0"] - 0.3), 2.0))
    # 패킹 12대: 6대 × 2줄(부산 4 × 2 와 같은 모양), 유리벽
    b.stations(rPK["x0"] + 0.3, round(rPK["z0"] + 0.65, 2), round(rPK["z1"] - 0.65, 2), 6, pitch=2.2)
    b.L["obstacles"].append({"rect": R(46.0, 23.6, 46.75, 25.0), "height": 1.6, "kind": "MATERIAL"})
    b.L["obstacles"].append({"rect": R(46.0, 27.0, 46.75, 28.4), "height": 1.6, "kind": "MATERIAL"})
    b.ship_racks([38.0, 42.0], 45.5)
    b.docks(50.6, 22.6, 9, 4)
    P = b.L["points"]
    P["truckDoor"] = {"x": 58.6, "z": 24.1}
    P["truckDock"] = {"x": 61.4, "z": 23.4}
    P["inboundIdle"] = [{"x": round(51.0 + 0.8 * i, 2), "z": round(27.6 + 0.9 * j, 2)} for j in range(2) for i in range(4)]
    P["cartDepot"] = {"x": 32.6, "z": 19.0}
    P["idle"] = [{"x": round(35.0 + 1.0 * i, 2), "z": round(18.2 + 0.8 * j, 2)} for j in range(2) for i in range(12)]
    P["packQueue"] = {"x": 48.0, "z": 26.1}
    P["shipDrop"] = {"x": 37.0, "z": 38.8}
    P["riderPickup"] = {"x": 40.0, "z": 47.4}
    P["riderParking"] = [{"x": round(46.0 + 1.2 * i, 2), "z": 47.0} for i in range(10)]
    P["riderEntry"] = {"x": 51.0, "z": 50.0}
    b.L["drawing"] = {
        "name": "세종 MFC(설계안)", "drawWidth": W + 7, "yard": R(30, BD, 60, BD + 8),
        "yardLabel": "1층(건물 앞) — 3층에서 리프트로 내려옴",
        "title": "세종지점 MFC 설계안 — 3층 한 층 812평(64 × 42 m 직사각형 가정) · 부산 진구점과 같은 방 구성·랙 수",
        "labelZ": {"A": 25.8, "B": 25.8, "C-H": 15.4, "ELV": 28.6, "LIFT": 36.8}, "doorLabels": [],
        "stationNote": "(번호, 6대 × 2줄)",
        "scaleNote": "위 = 북(z=0) · 가로 64 m × 건물 깊이 42 m (+ 1층 앞마당 8 m, 다른 층) · x = 동쪽, z = 남쪽",
        "notes": ["세종특별자치시 어진동 513, 지상 3층(평면은 3층 한 층)", "건물 모양·승강기·리프트 위치는 가정(실측 도면 아님)",
                  "방 구성·랙 수·칸 구조·SKU 는 부산 진구점과 같음", "넓은 면적 = 저온 보충재고·입고창고·패킹 12대",
                  "설명: layout-sejong/README.md"]}
    return b.finish(), {"A": (nA, capA), "B": (nB, capB), "AMB": (nC, capC)}, alloc


# ---------- 창원: 지하 1층 282평, 부산 진구점 배치를 줄여 넣음 — 남쪽 한 면(B1 하역장)으로 들어오고 나감(U) ----------
# 2026-11-21 재설계: 부산처럼 상온은 가로 줄(C~H), 저온실 안에 보충재고 팔레트·격리, 입고창고는 입고·검수 옆.
# 랙은 SKU 최소(85 %)를 넘는 만큼만 — 냉동 91·냉장 117·상온 140 (부산 105·125·138 보다 저온이 적은 것은 면적 때문, SKU 는 같고 85 % 안).
def build_changwon():
    W, BD = 36.0, 25.9          # 36 × 25.9 = 932.4 ㎡ = 282.05평 [가정 — 282평 직사각형]
    b = Builder(W, BD, 8.0)
    END_N = 0.8
    COLD_Z1 = 19.0
    # 냉동 A: 홑줄 + 겹줄 3 = 7줄 × 13랙 = 91
    rA = R(0, 0, 9.2, COLD_Z1)
    nA, capA, _ = b.rack_block("FROZEN", 0.1, END_N, "SDDDA", 13)
    # 냉장 B: 홑줄 + 겹줄 4 = 9줄 × 13랙 = 117
    rB = R(9.4, 0, 20.4, COLD_Z1)
    nB, capB, _ = b.rack_block("CHILLED", 9.5, END_N, "SDDDDA", 13)
    # 상온 C~H: 가로 줄 14줄(겹줄 7) × 10랙 = 140
    rC = R(20.6, 0, W, 13.6)
    run_c, _ = run_len(10)
    xc = round(rC["x0"] + (rC["x1"] - rC["x0"] - run_c) / 2, 2)
    amb_cap = 14 * 10
    alloc = allocate_ambient(amb_cap)
    nC, capC, _ = b.rack_block("AMBIENT", 0.9, xc, "DDDDDDDA", 10, along="x", groups=[(g, alloc[g]) for g in "CDEFGH"])
    # 남쪽: 패킹 | 휴게·사무 | 입고·검수(하역장 문) | 입고창고(동쪽, 상온 보충)
    S0 = 21.0
    rPK = R(0, S0, 10.0, BD)
    rOFF = R(10.2, S0, 16.0, BD)
    rRCV = R(16.2, S0, 26.0, BD)
    rWH = R(26.2, 13.8, W, BD)
    rAQ = R(6.4, COLD_Z1 - 3.6, 9.0, COLD_Z1 - 0.2)
    rBQ = R(17.6, COLD_Z1 - 3.6, 20.2, COLD_Z1 - 0.2)
    rCQ = R(W - 3.4, BD - 3.6, W - 0.2, BD - 0.2)
    Z = b.zone
    Z("A", "FROZEN", "냉동 A", rA, None)
    Z("B", "CHILLED", "냉장 B", rB, None)
    Z("C-H", "AMBIENT", "상온 C~H", rC, None)
    Z("WH", "INBOUND_STORE", "입고창고", rWH, None)
    Z("RCV", "RECEIVING", "입고·검수", rRCV, None)
    Z("OFF", "OFFICE", "휴게·사무", rOFF, None)
    Z("PK", "PACKING", "패킹", rPK, None)
    Z("AQ", "QUARANTINE", "냉동 격리", rAQ, 3)
    Z("BQ", "QUARANTINE", "냉장 격리", rBQ, 3)
    Z("CQ", "QUARANTINE", "상온 격리", rCQ, 3)
    Z("SHIP", "SHIPPING", "외부 출하대기(B1 하역장)", R(2.0, 27.6, 8.0, 30.2), 3)
    Z("PARK", "PARKING", "라이더 대기(B1 주차장)", R(12.0, 30.4, 24.0, 33.4))
    bw = b.box_walls
    bw(R(0, 0, W, BD), doors=[("S", 4.0, 6.0), ("S", 19.0, 23.0)])
    bw(rA, doors=[("S", 2.0, 4.5)])
    bw(rB, doors=[("S", 11.0, 13.5)])
    bw(rC, doors=[("S", 21.0, 23.5)])
    bw(rWH, doors=[("W", 14.4, 17.0), ("W", 22.0, 24.5)])
    bw(rRCV, doors=[("N", 17.0, 20.0), ("S", 19.0, 23.0)])
    bw(rOFF, doors=[("N", 11.5, 13.0)])
    bw(rPK, doors=[("N", 7.5, 9.5), ("S", 4.0, 6.0)], glass=True)
    for r in (rAQ, rBQ):
        bw(r, doors=[("W", r["z0"] + 0.4, r["z0"] + 1.6)])
    bw(rCQ, doors=[("W", rCQ["z0"] + 0.4, rCQ["z0"] + 1.6)])
    def strip(r, rq):
        x0 = r["x1"] - 0.1 - PAL
        items, z = [], 0.5
        while z + PAL <= rq["z0"] - 0.2:
            items.append((R(x0, z, x0 + PAL, z + PAL), {"x": round(x0 - 0.6, 3), "z": round(z + PAL / 2, 3)}))
            z += PAL + PAL_GAP + 0.2
        return items
    b.reserves("FROZEN", "AR", strip(rA, rAQ))
    b.reserves("CHILLED", "BR", strip(rB, rBQ))
    b.reserves("AMBIENT", "WR", pallet_grid(R(rWH["x0"] + 0.4, 14.3, rWH["x1"] - 0.2, rCQ["z0"] - 0.3), 1.8))
    b.stations(0.6, round(S0 + 0.6, 2), round(BD - 0.6, 2), 4)
    b.L["obstacles"].append({"rect": R(8.9, S0 + 1.6, 9.65, S0 + 3.0), "height": 1.6, "kind": "MATERIAL"})
    b.ship_racks([3.7, 6.3], 28.9)
    b.docks(16.8, 21.4, 9, 4)
    P = b.L["points"]
    P["truckDoor"] = {"x": 21.0, "z": BD - 0.6}
    P["truckDock"] = {"x": 21.0, "z": BD + 4.5}
    P["inboundIdle"] = [{"x": round(17.0 + 0.8 * i, 2), "z": round(24.6 + 0.6 * j, 2)} for j in range(2) for i in range(4)]
    P["cartDepot"] = {"x": 21.4, "z": 15.0}
    P["idle"] = [{"x": round(1.0 + 1.0 * i, 2), "z": round(19.6 + 0.7 * j, 2)} for j in range(2) for i in range(12)]
    P["packQueue"] = {"x": 9.0, "z": 20.4}
    P["shipDrop"] = {"x": 5.0, "z": BD + 0.6}
    P["riderPickup"] = {"x": 9.0, "z": 29.4}
    P["riderParking"] = [{"x": round(12.6 + 1.2 * i, 2), "z": 32.4} for i in range(10)]
    P["riderEntry"] = {"x": 18.0, "z": 33.9}
    b.L["drawing"] = {
        "name": "창원 MFC(설계안)", "drawWidth": W + 4, "yard": R(0, BD, 36, BD + 8),
        "yardLabel": "B1 하역장·주차장(경사로 → 지상)",
        "title": "창원지점 MFC 설계안 — 라이크빌 지하 1층 282평(36 × 25.9 m 직사각형 가정) · 부산 진구점 방 구성을 줄여 넣음",
        "labelZ": {"A": 9.5, "B": 9.5, "C-H": 13.0, "RCV": 22.4}, "doorLabels": [],
        "stationNote": "(번호, 4대 × 2줄)",
        "scaleNote": "위 = 북(z=0) · 가로 36 m × 깊이 25.9 m (+ B1 하역장 8 m) · x = 동쪽, z = 남쪽",
        "notes": ["창원시 성산구 용호동 73-2 라이크빌 B1", "건물 모양·문·경사로 위치는 가정(실측 도면 아님)",
                  "방 구성·칸 구조·SKU 는 부산 진구점과 같음, 랙은 SKU 85 % 를 넘는 만큼",
                  "입고·출고 모두 남쪽 B1 하역장(U 흐름)", "설명: layout-changwon/README.md"]}
    return b.finish(), {"A": (nA, capA), "B": (nB, capB), "AMB": (nC, capC)}, alloc


def write(L, sub):
    d = os.path.join(MFC_DIR, sub)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "layout.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(L, f, ensure_ascii=False, indent=1)
        f.write("\n")


def summary(name, L, fill, alloc):
    print(f"== {name}: {L['width']} × {L['buildingDepth']} m = {L['width'] * L['buildingDepth']:.1f} ㎡ = {L['width'] * L['buildingDepth'] / PY:.1f}평")
    for k, (n, cap) in fill.items():
        print(f"  {k}: 랙 {n} / 수용 {cap}")
    print("  상온 배분", alloc)
    for z in L["zones"]:
        print(f"  {z['id']:7s} {z['kind']:14s} {area(z['rect']):7.1f} ㎡ {area(z['rect']) / PY:6.1f}평")
    print("  팔레트", {t: sum(1 for r in L["reserves"] if r["temp"] == t) for t in ("FROZEN", "CHILLED", "AMBIENT")},
          "패킹대", len(L["stations"]), "칸", L["locationCount"])


if __name__ == "__main__":
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    for name, fn, sub in (("세종", build_sejong, "layout-sejong"), ("창원", build_changwon, "layout-changwon")):
        L, fill, alloc = fn()
        write(L, sub)
        summary(name, L, fill, alloc)
