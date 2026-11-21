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


# ---------- 세종: 3층, 직선(I) 흐름, 가운데 등뼈 통로 + 긴 빗살 통로 ----------
def build_sejong():
    W, BD = 64.0, 42.0          # 64 × 42 = 2,688 ㎡ = 813.1평 [가정 — 812평 직사각형]
    b = Builder(W, BD, 8.0)
    NB = 20.0                    # 북쪽 띠(보관실) 깊이
    SP0, SP1 = 20.2, 23.0        # 등뼈 주 통로 2.8 m(양방향 카트 교행)
    S0 = 23.2
    END_N, END_S = 1.0, 1.3
    n_along = max_run(NB - END_N - END_S)     # 13
    pat_cold = "SDDDDA"                        # 9줄
    pat_amb = "SDDDDDA"                        # 11줄
    x = 0.0
    # 냉동 A
    _, wA = across(pat_cold)
    rA = R(0, 0, round(wA + 0.25, 2), NB)
    nA, capA, _ = b.rack_block("FROZEN", 0.0, END_N, pat_cold, n_along)
    x = rA["x1"] + 0.2
    _, wB = across(pat_cold)
    rB = R(x, 0, round(x + wB + 0.25, 2), NB)
    nB, capB, _ = b.rack_block("CHILLED", x, END_N, pat_cold, n_along)
    x = rB["x1"] + 0.2
    _, wC = across(pat_amb)
    rC = R(x, 0, round(x + wC + 0.25, 2), NB)
    amb_cap = len(across(pat_amb)[0]) * n_along
    alloc = allocate_ambient(amb_cap)
    nC, capC, _ = b.rack_block("AMBIENT", x, END_N, pat_amb, n_along, groups=[(g, alloc[g]) for g in "CDEFGH"])
    x = rC["x1"] + 0.2
    rWH = R(x, 0, 44.0, NB)
    rRCV = R(54.2, 0, W, NB)
    rWH2 = R(44.2, 0, 54.0, NB)
    # 남쪽 띠
    rPK = R(0, S0, 15.0, 31.4)
    rLIFT = R(0, 31.6, 6.0, BD)
    rOFF = R(6.2, 31.6, 15.0, BD)
    rAR = R(15.2, S0, 25.0, BD)
    rBR = R(25.2, S0, 35.0, BD)
    rCQ = R(35.2, S0, 39.0, 26.9)
    rRTN = R(35.2, 27.1, 44.0, BD)
    rMAT = R(44.2, S0, 54.0, BD)
    rLIFT_IN = R(54.2, S0, W, BD)
    rAQ = R(rAR["x0"] + 0.2, BD - 3.8, rAR["x0"] + 3.8, BD - 0.2)
    rBQ = R(rBR["x0"] + 0.2, BD - 3.8, rBR["x0"] + 3.8, BD - 0.2)
    Z = b.zone
    Z("A", "FROZEN", "냉동 A(피킹)", rA, None)
    Z("B", "CHILLED", "냉장 B(피킹)", rB, None)
    Z("C-H", "AMBIENT", "상온 C~H", rC, None)
    Z("WH", "INBOUND_STORE", "입고창고(상온 보충)", rWH, None)
    Z("WH2", "SPARE", "증설 예비(상온 랙 2차)", rWH2, None)
    Z("RCV", "RECEIVING", "입고·검수", rRCV, None)
    Z("AR", "FROZEN", "냉동 보충재고실", rAR, None)
    Z("BR", "CHILLED", "냉장 보충재고실", rBR, None)
    Z("PK", "PACKING", "패킹(12대)", rPK, None)
    Z("LIFT", "LIFT", "출하 리프트", rLIFT, None)
    Z("OFF", "OFFICE", "휴게·사무", rOFF, None)
    Z("RTN", "RECEIVING", "회수·반품 검수", rRTN, None)
    Z("MAT", "SPARE", "포장 부자재·예비", rMAT, None)
    Z("LIFTIN", "LIFT", "화물 승강기 2대·로비", rLIFT_IN, None)
    Z("AQ", "QUARANTINE", "냉동 격리", rAQ, 4)
    Z("BQ", "QUARANTINE", "냉장 격리", rBQ, 4)
    Z("CQ", "QUARANTINE", "상온 격리", rCQ, 4)
    Z("SHIP", "SHIPPING", "1층 출하대기(리프트 아래)", R(1.0, 44.2, 9.0, 46.8), 3)
    Z("PARK", "PARKING", "라이더 대기(1층)", R(10.0, 44.6, 22.0, 48.0))
    # 벽(문 = 등뼈 통로 쪽 남벽/북벽의 끊긴 곳)
    bw = b.box_walls
    bw(R(0, 0, W, BD), doors=[("E", 24.5, 29.5), ("S", 2.0, 4.0)])
    for r in (rA, rB, rC):
        bw(r, doors=[("S", r["x0"] + 0.5, r["x0"] + 2.3), ("S", r["x1"] - 2.3, r["x1"] - 0.5)])
    bw(rWH, doors=[("S", rWH["x0"] + 1.0, rWH["x0"] + 3.5), ("E", 2.0, 5.0)])
    bw(rWH2, doors=[("S", rWH2["x0"] + 1.0, rWH2["x0"] + 3.5), ("W", 2.0, 5.0), ("E", 2.0, 5.0)])
    bw(rRCV, doors=[("S", rRCV["x0"] + 1.0, rRCV["x1"] - 1.0)])
    for r in (rAR, rBR):
        bw(r, doors=[("N", r["x0"] + 4.0, r["x0"] + 6.0)])
    bw(rPK, doors=[("N", 11.0, 13.5), ("S", 2.0, 4.0)], glass=True)
    bw(rLIFT, doors=[("N", 2.0, 4.0)])
    bw(rOFF, doors=[("N", 7.0, 8.5)])
    bw(rCQ, doors=[("N", 36.0, 38.0)])
    bw(rRTN, doors=[("N", 40.0, 42.0)])
    bw(rMAT, doors=[("N", 45.0, 47.0)])
    bw(rLIFT_IN, doors=[("N", 55.2, 63.0)])
    # 보충재고 팔레트
    b.reserves("FROZEN", "AR", pallet_grid(R(rAR["x0"] + 0.3, S0 + 2.0, rAR["x1"] - 0.3, BD - 0.4), 1.5, skip=[rAQ]))
    b.reserves("CHILLED", "BR", pallet_grid(R(rBR["x0"] + 0.3, S0 + 2.0, rBR["x1"] - 0.3, BD - 0.4), 1.5, skip=[rBQ]))
    b.reserves("AMBIENT", "WR", pallet_grid(R(rWH["x0"] + 0.4, 0.6, rWH["x1"] - 0.3, NB - 1.4), 2.4))
    # 패킹 12대: 6대 × 2줄, 가운데 통로 — 모듈 2.0 × 2.2(7-3)
    b.stations(0.2, round(S0 + 0.65, 2), round(rPK["z1"] - 0.65, 2), 6)
    b.L["obstacles"].append({"rect": R(13.0, S0 + 1.6, 13.75, S0 + 3.0), "height": 1.6, "kind": "MATERIAL"})
    b.L["obstacles"].append({"rect": R(13.0, S0 + 4.2, 13.75, S0 + 5.6), "height": 1.6, "kind": "MATERIAL"})
    b.ship_racks([3.0, 7.0], 45.5)
    b.docks(55.2, 1.5, 9, 4)
    P = b.L["points"]
    P["truckDoor"] = {"x": W - 0.8, "z": 27.0}
    P["truckDock"] = {"x": W + 4.0, "z": 27.0}
    P["inboundIdle"] = [{"x": round(55.4 + 0.8 * i, 2), "z": round(8.0 + 0.9 * j, 2)} for j in range(2) for i in range(4)]
    P["cartDepot"] = {"x": 13.8, "z": 21.6}
    P["idle"] = [{"x": round(16.2 + 1.0 * i, 2), "z": round(21.0 + 0.8 * j, 2)} for j in range(2) for i in range(12)]
    P["packQueue"] = {"x": 12.2, "z": 27.3}
    P["shipDrop"] = {"x": 3.0, "z": BD + 0.6}
    P["riderPickup"] = {"x": 5.0, "z": 47.4}
    P["riderParking"] = [{"x": round(11.0 + 1.2 * i, 2), "z": 47.0} for i in range(10)]
    P["riderEntry"] = {"x": 16.0, "z": 50.0}
    b.L["drawing"] = {
        "name": "세종 MFC(설계안)", "drawWidth": W + 7, "yard": R(0, BD, 24, BD + 8),
        "yardLabel": "1층(건물 앞) — 3층에서 리프트로 내려옴",
        "title": "세종지점 MFC 설계안 — 3층 812평(64 × 42 m 직사각형 가정) · 직선 흐름 · 긴 빗살 통로",
        "labelZ": {"A": 10.5, "B": 10.5, "C-H": 10.5}, "doorLabels": [],
        "stationNote": "(번호, 6대 × 2줄)",
        "scaleNote": "위 = 북(z=0) · 가로 64 m × 건물 깊이 42 m (+ 1층 앞마당 8 m, 다른 층) · x = 동쪽, z = 남쪽",
        "notes": ["세종특별자치시 어진동 513, 지상 3층(평면은 3층)", "건물 모양·문·승강기 위치는 가정(실측 도면 아님)",
                  "랙 수 = 기하 배치(통로 0.7 m·끝 여유·횡단 1.2 m)",
                  "칸 구조·SKU 목표는 부산 진구점과 같음", "설명: layout-sejong/README.md"]}
    return b.finish(), {"A": (nA, capA), "B": (nB, capB), "AMB": (nC, capC)}, alloc


# ---------- 창원: 지하 1층, U 흐름(입·출고 같은 남쪽 면), 촘촘한 블록 ----------
def build_changwon():
    W, BD = 36.0, 25.9          # 36 × 25.9 = 932.4 ㎡ = 282.05평 [가정 — 282평 직사각형]
    b = Builder(W, BD, 8.0)
    NB = 16.0
    END_N, END_S = 0.8, 1.2
    n_along = max_run(NB - END_N - END_S)     # 11, 횡단 통로 없음
    FA0, FA1 = 16.2, 18.2                      # 앞 통로 2.0 m (U 고리)
    S0 = 18.4
    # 냉동: 9줄 + 동쪽 팔레트 열(통로 1.2 + 팔레트 1.1)
    pat_f, pat_c, pat_a = "SDDDDA", "SDDDDDA", "SDDDDDS"
    _, wA = across(pat_f)
    pal_strip = 1.2 + PAL + 0.2
    rA = R(0, 0, round(wA - AISLE + pal_strip, 2), NB)          # 마지막 0.7 통로 대신 팔레트 접근 통로 1.2
    nA, capA, _ = b.rack_block("FROZEN", 0.0, END_N, pat_f, n_along)
    x = rA["x1"] + 0.2
    _, wB = across(pat_c)
    rB = R(x, 0, round(x + wB - AISLE + pal_strip, 2), NB)
    nB, capB, _ = b.rack_block("CHILLED", x, END_N, pat_c, n_along)
    x = rB["x1"] + 0.2
    _, wC = across(pat_a)
    rC = R(x, 0, round(x + wC + 0.1, 2), NB)
    amb_cap = len(across(pat_a)[0]) * n_along
    alloc = allocate_ambient(amb_cap)
    nC, capC, _ = b.rack_block("AMBIENT", x + 0.05, END_N, pat_a, n_along, groups=[(g, alloc[g]) for g in "CDEFGH"])
    x = rC["x1"] + 0.2
    rWH = R(x, 0, W, NB)
    rPK = R(0, S0, 10.6, BD)
    rOFF = R(10.8, S0, 17.4, BD)
    rRCV = R(17.6, S0, W, BD)
    # 격리: 저온실 팔레트 열 남쪽 끝, 상온은 입고창고 남동쪽
    rAQ = R(rA["x1"] - 2.4, NB - 4.8, rA["x1"] - 0.1, NB - 0.1)
    rBQ = R(rB["x1"] - 2.4, NB - 4.8, rB["x1"] - 0.1, NB - 0.1)
    rCQ = R(W - 3.2, NB - 4.3, W - 0.1, NB - 0.1)
    Z = b.zone
    Z("A", "FROZEN", "냉동 A", rA, None)
    Z("B", "CHILLED", "냉장 B", rB, None)
    Z("C-H", "AMBIENT", "상온 C~H", rC, None)
    Z("WH", "INBOUND_STORE", "입고창고", rWH, None)
    Z("RCV", "RECEIVING", "입고·검수·회수", rRCV, None)
    Z("OFF", "OFFICE", "휴게·사무", rOFF, None)
    Z("PK", "PACKING", "패킹(8대)", rPK, None)
    Z("AQ", "QUARANTINE", "냉동 격리", rAQ, 4)
    Z("BQ", "QUARANTINE", "냉장 격리", rBQ, 4)
    Z("CQ", "QUARANTINE", "상온 격리", rCQ, 4)
    Z("SHIP", "SHIPPING", "외부 출하대기(B1 하역장)", R(2.0, 27.6, 8.0, 30.2), 3)
    Z("PARK", "PARKING", "라이더 대기(B1 주차장)", R(12.0, 30.4, 24.0, 33.4))
    bw = b.box_walls
    bw(R(0, 0, W, BD), doors=[("S", 4.0, 6.0), ("S", 27.0, 31.0)])
    for r in (rA, rB, rC):
        bw(r, doors=[("S", r["x0"] + 0.5, r["x0"] + 2.0), ("S", r["x1"] - 2.0, r["x1"] - 0.5)])
    bw(rWH, doors=[("S", rWH["x0"] + 0.5, rWH["x1"] - 0.5)])
    bw(rPK, doors=[("N", 7.5, 9.5), ("S", 4.0, 6.0)], glass=True)
    bw(rOFF, doors=[("N", 12.0, 13.5)])
    bw(rRCV, doors=[("N", 18.6, 21.0), ("N", 30.6, 34.0), ("S", 27.0, 31.0)])
    for r in (rAQ, rBQ):
        bw(r, doors=[("W", r["z0"] + 0.5, r["z0"] + 1.7)])
    bw(rCQ, doors=[("W", rCQ["z0"] + 0.5, rCQ["z0"] + 1.7)])
    # 팔레트: 저온실 동쪽 한 열(격리 위쪽), 입고창고 두 열
    def strip(r, rq):
        x0 = r["x1"] - 0.1 - PAL
        items = []
        z = 0.5
        while z + PAL <= rq["z0"] - 0.2:
            items.append((R(x0, z, x0 + PAL, z + PAL), {"x": round(x0 - 0.6, 3), "z": round(z + PAL / 2, 3)}))
            z += PAL + PAL_GAP + 0.2
        return items
    b.reserves("FROZEN", "AR", strip(rA, rAQ))
    b.reserves("CHILLED", "BR", strip(rB, rBQ))
    b.reserves("AMBIENT", "WR", pallet_grid(R(rWH["x0"] + 0.2, 0.5, rWH["x1"] - 0.1, rCQ["z0"] - 0.3), 1.8))
    b.reserves("AMBIENT", "WR", pallet_grid(R(rWH["x0"] + 0.2, rCQ["z0"], rCQ["x0"] - 0.2, NB - 0.3), 1.8, cols=1))
    # 패킹 8대: 4대 × 2줄, 모듈 2.0 × 2.2(7-5), 가운데 통로
    b.stations(0.2, round(S0 + 0.6, 2), round(BD - 0.6, 2), 4)
    b.L["obstacles"].append({"rect": R(8.6, S0 + 2.0, 9.35, S0 + 3.4), "height": 1.6, "kind": "MATERIAL"})
    b.L["obstacles"].append({"rect": R(9.6, S0 + 2.0, 10.35, S0 + 3.4), "height": 1.6, "kind": "MATERIAL"})
    b.ship_racks([3.7, 6.3], 28.9)
    b.docks(18.6, 19.3, 9, 4)
    P = b.L["points"]
    P["truckDoor"] = {"x": 29.0, "z": BD - 0.6}
    P["truckDock"] = {"x": 29.0, "z": BD + 4.5}
    P["inboundIdle"] = [{"x": round(26.0 + 0.8 * i, 2), "z": round(19.5 + 0.9 * j, 2)} for j in range(2) for i in range(4)]
    P["cartDepot"] = {"x": 10.0, "z": 17.2}
    P["idle"] = [{"x": round(11.0 + 1.0 * i, 2), "z": round(16.8 + 0.8 * j, 2)} for j in range(2) for i in range(12)]
    P["packQueue"] = {"x": 9.0, "z": 22.2}
    P["shipDrop"] = {"x": 5.0, "z": BD + 0.6}
    P["riderPickup"] = {"x": 9.0, "z": 29.4}
    P["riderParking"] = [{"x": round(12.6 + 1.2 * i, 2), "z": 32.4} for i in range(10)]
    P["riderEntry"] = {"x": 18.0, "z": 33.9}
    b.L["drawing"] = {
        "name": "창원 MFC(설계안)", "drawWidth": W + 4, "yard": R(0, BD, 36, BD + 8),
        "yardLabel": "B1 하역장·주차장(경사로 → 지상)",
        "title": "창원지점 MFC 설계안 — 라이크빌 지하 1층 282평(36 × 25.9 m 직사각형 가정) · U 흐름 · 촘촘한 블록",
        "labelZ": {"A": 8.0, "B": 8.0, "C-H": 8.0}, "doorLabels": [],
        "stationNote": "(번호, 4대 × 2줄)",
        "scaleNote": "위 = 북(z=0) · 가로 36 m × 깊이 25.9 m (+ B1 하역장 8 m) · x = 동쪽, z = 남쪽",
        "notes": ["창원시 성산구 용호동 73-2 라이크빌 B1", "건물 모양·문·경사로 위치는 가정(실측 도면 아님)",
                  "랙 수 = 기하 배치(통로 0.7 m·끝 여유, 횡단 통로 없음 11랙 줄)",
                  "칸 구조·SKU 목표는 부산 진구점과 같음", "설명: layout-changwon/README.md"]}
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
