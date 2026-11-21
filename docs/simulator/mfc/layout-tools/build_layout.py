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


# ---------- 부산 진구점 비율로 키우고 줄이기(2026-11-21 사용자 검토 2·3차) ----------
# 사용자: "기준 MFC 설계도 비율대로", "다품종 소량 적재 — 평수에 맞게 랙 갯수를 늘리고 줄이는 것뿐",
#        "입고검수는 반드시 냉동구역 근처", "직사각형 아니어도 되니까 평수 정확하게".
# → 방 평수 = 부산 문서 평수(MFC 문서 1장 표) × (센터 평수 / 463). 건물 바닥 = 정확히 812평·282평.
#   랙·보충 팔레트·패킹대·하차 칸도 같은 비율. 랙 모양(1.25 × 0.45 m)·통로 0.7 m·칸 구조는 그대로. SKU 판정 없음(다품종 소량 적재).
BUSAN_PY = 463.0
BUSAN_DOC = {"A": 109, "B": 100, "C-H": 65, "WH": 55, "RCV": 22, "OFF": 18, "PK": 24, "Q": 4, "SHIP": 3}
BUSAN_RACKS = {"A": 105, "B": 125, "AMB": 138}
BUSAN_AMB = {"C": 24, "D": 24, "E": 21, "F": 24, "G": 25, "H": 20}


def m2(zone, ratio):
    """부산 문서 평수 × 비율 → ㎡."""
    return BUSAN_DOC[zone] * ratio * PY


def split_amb(total):
    """상온 랙을 부산 C~H 비율로(가장 큰 나머지 방식)."""
    raw = {g: BUSAN_AMB[g] * total / BUSAN_RACKS["AMB"] for g in "CDEFGH"}
    out = {g: int(raw[g]) for g in raw}
    for g in sorted(raw, key=lambda g: raw[g] - out[g], reverse=True)[: total - sum(out.values())]:
        out[g] += 1
    return out


def cold_block(b, temp, letter, room, target, z_start, max_len):
    """겹줄 k 개 × n 랙(부산 저온실처럼 방 가운데, 바깥 통로 포함). 길이가 max_len 안인 가장 적은 k."""
    for k in range(1, 12):
        n = math.ceil(target / (2 * k))
        if run_len(n)[0] <= max_len:
            break
    pat = "D" * k + "A"
    _, w = across(pat)
    assert w <= room["x1"] - room["x0"] - 0.4, (letter, w)
    x0 = round((room["x0"] + room["x1"] - w) / 2, 2)
    placed, cap, _ = b.rack_block(temp, x0, z_start, pat, n, groups=[(letter, target)])
    return placed, cap, z_start + run_len(n)[0]


def amb_block(b, room, target, z_start):
    """상온 가로 줄(부산처럼 동서 방향, 횡단 통로 없이 12랙 이하)."""
    n = min(MAX_RUN, max_run(room["x1"] - room["x0"] - 1.6))
    k = math.ceil(target / (2 * n))
    pat = "D" * k + "A"
    _, w = across(pat)
    assert z_start + w <= room["z1"] - 0.1, ("상온 방 깊이 부족", w)
    x0 = round(room["x0"] + (room["x1"] - room["x0"] - run_len(n)[0]) / 2, 2)
    alloc = split_amb(target)
    placed, cap, _ = b.rack_block("AMBIENT", z_start, x0, pat, n, along="x", groups=[(g, alloc[g]) for g in "CDEFGH"])
    return placed, cap, alloc


def scaled(n, ratio, minimum=1):
    return max(minimum, round(n * ratio))


def take(items, n):
    assert len(items) >= n, (len(items), n)
    return items[:n]


def square(x0, z1, a):
    """왼쪽 아래 모서리(x0, z1)에서 넓이 a ㎡ 정사각형."""
    q = round(math.sqrt(a), 3)
    return R(x0, z1 - q, x0 + q, z1)


# ---------- 세종: 3층 한 층 812평 ----------
# 2026-11-21 사용자 검토 6차: "세종에 북쪽 통로가 없네", "입고검수가 북쪽에 있는데 … 냉동·냉장 출입구는 오직 하나만, 남쪽에 있네?"
# → 북쪽 벽을 따라 통로(입고·검수·냉동·냉장 문이 모두 여기로).
# 7차("냉동냉장 북쪽남쪽 문 2개", "왜 남쪽 통로를 또 없앴니"): 냉동·냉장 문 = 북쪽 하나 + 남쪽 하나, 남쪽 통로 되살림.
def build_sejong():
    ratio = 812.0 / BUSAN_PY                   # 1.7538
    W = 64.0
    BD = round(812.0 * PY / W, 3)              # 41.942 m → 바닥 정확히 812평
    b = Builder(W, BD, 8.0)
    NC = 2.4                                   # 북쪽 통로 폭(카트 양방향)
    SC = 2.4                                   # 남쪽 통로 폭
    Z0 = NC + 0.2                              # 왼쪽 열·저온실 북쪽 벽
    Z1 = round(BD - SC - 0.2, 3)               # 왼쪽 열·저온실 남쪽 벽
    D = Z1 - Z0
    OFF_M2 = 18.0 * PY                         # 휴게·사무 18평 고정(사용자)
    PASS = 1.3                                 # 입고·검수 → 휴게·사무 통로
    wL = 7.4
    rRCV = R(0, Z0, wL, round(Z0 + m2("RCV", ratio) / wL, 3))
    wW = round(wL - PASS - 0.2, 3)
    rELV = R(0, rRCV["z1"] + 0.2, wW, round(rRCV["z1"] + 0.2 + 45.0 / wW, 3))
    rOFF = R(0, rELV["z1"] + 0.2, wW, round(rELV["z1"] + 0.2 + OFF_M2 / wW, 3))
    assert rOFF["z1"] <= Z1, rOFF
    x = wL + 0.2
    rA = R(x, Z0, round(x + m2("A", ratio) / D, 3), Z1)
    x = rA["x1"] + 0.2
    rB = R(x, Z0, round(x + m2("B", ratio) / D, 3), Z1)
    rN = R(0, 0, rB["x1"], NC)                 # 북쪽 통로(왼쪽 열·냉동·냉장 위)
    wWH = round(m2("WH", ratio) / BD, 3)
    rWH = R(round(W - wWH, 3), 0, W, BD)
    # 8차(부산 진구점 새 평면 참고): 냉장 동쪽 세로 통로 1.4 m(북쪽 통로 ↔ 남쪽 통로), 상온·패킹·출하대기는 벽 없이 한 바닥
    VP = 1.4
    xv0 = rB["x1"] + 0.2
    xc0, xc1 = round(xv0 + VP + 0.2, 3), rWH["x0"] - 0.2
    rC = R(xc0, 0, xc1, round(m2("C-H", ratio) / (xc1 - xc0), 3))
    pk_w = xc1 - xc0
    pk_x0 = xc0
    rPK = R(pk_x0, rC["z1"] + 0.6, xc1, round(rC["z1"] + 0.6 + m2("PK", ratio) / pk_w, 3))
    sh_w = 7.4
    rSHIP = R(round((xc0 + xc1 - sh_w) / 2, 3), rPK["z1"] + 0.3, round((xc0 + xc1 + sh_w) / 2, 3),
              round(rPK["z1"] + 0.3 + m2("SHIP", ratio) / sh_w, 3))
    assert rSHIP["z1"] <= BD, rSHIP
    qa = BUSAN_DOC["Q"] * ratio * PY
    rAQ = square(rA["x0"] + 0.3, Z1 - 0.3, qa)
    rBQ = square(rB["x0"] + 0.3, Z1 - 0.3, qa)
    rCQ = square(rWH["x0"] + 0.2, BD - 0.3, qa)
    tA, tB, tC = (scaled(BUSAN_RACKS[k], ratio) for k in ("A", "B", "AMB"))
    nA, capA, zA = cold_block(b, "FROZEN", "A", rA, tA, Z0 + 1.2, 31.0)
    nB, capB, zB = cold_block(b, "CHILLED", "B", rB, tB, Z0 + 1.2, 31.0)
    nC, capC, alloc = amb_block(b, rC, tC, 0.9)
    Z = b.zone
    Z("RCV", "RECEIVING", "입고·검수", rRCV, round(BUSAN_DOC["RCV"] * ratio, 1))
    Z("ELV", "LIFT", "화물 승강기 2대", rELV, None)
    Z("OFF", "OFFICE", "휴게·사무", rOFF, 18)
    Z("A", "FROZEN", "냉동 A", rA, round(BUSAN_DOC["A"] * ratio, 1))
    Z("B", "CHILLED", "냉장 B", rB, round(BUSAN_DOC["B"] * ratio, 1))
    Z("C-H", "AMBIENT", "상온 C~H", rC, round(BUSAN_DOC["C-H"] * ratio, 1))
    Z("PK", "PACKING", "패킹", rPK, round(BUSAN_DOC["PK"] * ratio, 1))
    Z("SHIP", "SHIPPING", "출하대기", rSHIP, round(BUSAN_DOC["SHIP"] * ratio, 1))
    Z("WH", "INBOUND_STORE", "입고창고", rWH, round(BUSAN_DOC["WH"] * ratio, 1))
    Z("AQ", "QUARANTINE", "냉동 격리", rAQ, round(BUSAN_DOC["Q"] * ratio, 1))
    Z("BQ", "QUARANTINE", "냉장 격리", rBQ, round(BUSAN_DOC["Q"] * ratio, 1))
    Z("CQ", "QUARANTINE", "상온 격리", rCQ, round(BUSAN_DOC["Q"] * ratio, 1))
    Z("PARK", "PARKING", "라이더 대기(1층)", R(36.0, BD + 2.4, 56.0, BD + 6.0))
    Z("TRUCK", "PARKING", "입고 차량 진입로(1층)", R(-7.6, rELV["z0"] - 2.0, -0.4, rELV["z1"] + 2.0))
    bw = b.box_walls
    bw(R(0, 0, W, BD))
    # 문(흐름마다 하나, 가장 짧은 쪽):
    #   승강기: 서쪽(1층 진입로) · 북쪽(입고·검수로 바로 올림)
    #   입고·검수 → 북쪽 통로(저온은 냉동·냉장 북쪽 문, 상온은 통로 동쪽 끝 → 상온 → 입고창고) / → 남쪽 통로(휴게·사무)
    #   냉동 A·냉장 B: 북쪽 통로 문(입고) + 남쪽 통로 문(피킹 — 가운데 작업 바닥·패킹 쪽, 동쪽 끝)
    #   상온 C~H·패킹·출하대기: 벽 없이 한 바닥(서쪽은 세로 통로로 열림)
    #   입고창고: 서쪽 벽 문 둘(상온 줄 끝으로 보충 · 패킹 쪽)
    bw(rRCV, doors=[("N", 0.6, wL - 0.6), ("S", wW + 0.1, wL - 0.1)])
    bw(rELV, doors=[("W", rELV["z0"] + 1.0, rELV["z1"] - 1.0), ("N", 0.4, wW - 0.4)])
    bw(rOFF, doors=[("E", rOFF["z0"] + 0.6, rOFF["z0"] + 2.4)])
    a_mid, b_mid = (rA["x0"] + rA["x1"]) / 2, (rB["x0"] + rB["x1"]) / 2
    bw(rA, doors=[("N", a_mid - 1.5, a_mid + 1.5), ("S", rA["x1"] - 3.4, rA["x1"] - 0.6)])
    bw(rB, doors=[("N", b_mid - 1.5, b_mid + 1.5), ("S", rB["x1"] - 3.4, rB["x1"] - 0.6)])
    bw(rWH, doors=[("W", 6.0, 10.0), ("W", rC["z1"] + 2.0, rC["z1"] + 5.0)])
    b.reserves("FROZEN", "AR", take(pallet_grid(R(rAQ["x1"] + 0.6, zA + 1.2, rA["x1"] - 3.8, Z1 - 0.4), 1.5, axis="x"), scaled(4, ratio)))
    b.reserves("CHILLED", "BR", take(pallet_grid(R(rBQ["x1"] + 0.6, zB + 1.2, rB["x1"] - 3.8, Z1 - 0.4), 1.5, axis="x"), scaled(4, ratio)))
    b.reserves("AMBIENT", "WR", take(pallet_grid(R(rWH["x0"] + 0.4, 0.6, rWH["x1"] - 0.3, rCQ["z0"] - 0.4), 1.8), scaled(24, ratio)))
    b.stations(rPK["x0"] + 0.2, round(rPK["z0"] + 0.65, 2), round(rPK["z1"] - 0.65, 2), math.ceil(scaled(8, ratio) / 2), pitch=1.95)
    b.ship_racks([rSHIP["x0"] + 1.2, rSHIP["x1"] - 1.2], round(rSHIP["z0"] + 0.8, 2))
    b.docks(0.6, rRCV["z0"] + 0.8, 9, scaled(36, ratio) // 9)
    P = b.L["points"]
    P["truckDoor"] = {"x": round(wW / 2, 2), "z": round((rELV["z0"] + rELV["z1"]) / 2, 2)}
    P["truckDock"] = {"x": -4.0, "z": round((rELV["z0"] + rELV["z1"]) / 2, 2)}
    P["inboundIdle"] = [{"x": round(0.8 + 0.8 * i, 2), "z": round(rRCV["z1"] - 1.4 + 0.7 * j, 2)} for j in range(2) for i in range(4)]
    P["cartDepot"] = {"x": round(xv0 + 0.7, 2), "z": round(rC["z1"] + 0.3, 2)}
    P["idle"] = [{"x": round(xv0 + 0.4 + 0.6 * j, 2), "z": round(6.0 + 0.8 * i, 2)} for j in range(2) for i in range(12)]
    P["packQueue"] = {"x": round(rPK["x0"] + 6.0, 2), "z": round(rPK["z0"] + 0.2, 2)}
    P["shipDrop"] = {"x": round(rSHIP["x0"] - 2.4, 2), "z": round(rSHIP["z0"] + 0.4, 2)}
    P["riderPickup"] = {"x": round(rSHIP["x1"] + 0.6, 2), "z": round(rSHIP["z1"] - 0.6, 2)}
    P["riderParking"] = [{"x": round(38.0 + 1.6 * i, 2), "z": round(BD + 4.8, 2)} for i in range(10)]
    P["riderEntry"] = {"x": 57.0, "z": round(BD + 4.0, 2)}
    b.L["drawing"] = {
        "name": "세종 MFC(설계안)", "drawWidth": W + 7, "drawLeft": 8.0, "yard": R(34, BD, 58, BD + 8),
        "yardLabel": "1층 — 라이더는 승객용 승강기로 3층 출하대기에서 픽업(가정)",
        "title": "세종지점 MFC 설계안 — 3층 한 층 812평 · 부산 진구점 평수 × 812/463",
        "labelZ": {"A": 36.6, "B": 36.6, "C-H": round(rC["z1"] - 0.8, 2), "TRUCK": round(rELV["z1"] + 1.2, 2),
                   "SHIP": round(rSHIP["z1"] - 0.8, 2), "RCV": round(rRCV["z0"] + 9.0, 2)}, "doorLabels": [],
        "stationNote": "(번호, 7대 × 2줄)",
        "scaleNote": f"위 = 북(z=0) · 가로 64 m × 깊이 {BD} m = 812평 (+ 1층 8 m, 다른 층) · x = 동쪽, z = 남쪽",
        "notes": ["세종특별자치시 어진동 513, 지상 3층(평면은 3층 한 층)", "건물 모양·승강기 위치는 가정(실측 도면 아님)",
                  "방 평수·랙·팔레트·패킹대 = 부산 진구점 × 812/463, 휴게·사무 18평",
                  "냉동·냉장 문 = 북쪽(입고) + 남쪽(피킹), 북쪽·남쪽 통로", "설명: layout-sejong/README.md"]}
    return b.finish(), {"A": (nA, capA), "B": (nB, capB), "AMB": (nC, capC)}, alloc


# ---------- 창원: 지하 1층 282평 ----------
# 2026-11-21 사용자 검토 8차: 부산 진구점 새 평면(사용자 그림)과 같은 통로·문 — 휴게·사무는 없음(그 평수는 상온 입고창고).
#   북쪽 통로(냉장·냉동 위) · 남쪽 통로(냉장·냉동·상온 아래 → 패킹) · 냉동 동쪽 세로 통로(북쪽 ↔ 남쪽).
#   냉장·냉동 = 북쪽 입고문 + 남쪽 출고문(→ 패킹). 가운데 열 = 입고·검수(북쪽 차량문) → 가운데 통로·장바구니 거치대 → 상온 C~H(벽 없음).
#   동쪽 열 = 상온 입고창고(서쪽 문 둘: 가운데 통로·상온, 격리) → 패킹(상온과 벽 없음) → 출하대기(남쪽).
def build_changwon():
    ratio = 282.0 / BUSAN_PY                   # 0.6091
    W = 36.0
    BD = round(282.0 * PY / W, 3)              # 25.895 m → 바닥 정확히 282평
    b = Builder(W, BD, 8.0)
    NC, SC, VP = 1.5, 1.5, 1.0                 # 북쪽·남쪽 통로, 세로 통로 폭
    # 9차(사용자 "냉장이랑 냉동 각각 6평씩 줄여서 상온구역 늘려"): 냉장·냉동 −6평씩, 상온 +12평.
    # 랙은 평수를 따른다(부산 평당 랙: 냉장 125/100 · 냉동 105/109 · 상온 138/65).
    PY_B = BUSAN_DOC["B"] * ratio - 6.0
    PY_A = BUSAN_DOC["A"] * ratio - 6.0
    PY_C = BUSAN_DOC["C-H"] * ratio + 12.0
    Z0 = NC + 0.2
    Z1 = round(BD - SC - 0.2, 3)
    D = Z1 - Z0
    rB = R(0, Z0, round(PY_B * PY / D, 3), Z1)
    x = rB["x1"] + 0.2
    rA = R(x, Z0, round(x + PY_A * PY / D, 3), Z1)
    xv0 = rA["x1"] + 0.2                       # 세로 통로 xv0 ~ xv0 + VP
    xm0 = round(xv0 + VP + 0.2, 3)
    wh_m2 = m2("WH", ratio) + m2("OFF", ratio)   # 휴게·사무 자리까지 상온 입고창고(사용자)
    wr = round((wh_m2 + m2("PK", ratio)) / (BD - 0.2), 3)
    xr0 = round(W - wr, 3)
    xm1 = round(xr0 - 0.2, 3)
    wm = xm1 - xm0
    rRCV = R(xm0, 0, xm1, round(m2("RCV", ratio) / wm, 3))
    rC = R(xm0, round(Z1 - PY_C * PY / wm, 3), xm1, Z1)
    rCA = R(xm0, rRCV["z1"] + 0.2, xm1, rC["z0"])          # 가운데 통로·장바구니 거치대(남는 넓이)
    assert rCA["z1"] - rCA["z0"] >= 1.5, rCA
    rWH = R(xr0, 0, W, round(wh_m2 / wr, 3))
    rPK = R(xr0, rWH["z1"] + 0.2, W, BD)
    sh_w = 3.4
    rSHIP = R(round(xr0 + (wr - sh_w) / 2, 3), BD + 0.4, round(xr0 + (wr + sh_w) / 2, 3), round(BD + 0.4 + m2("SHIP", ratio) / sh_w, 3))
    qa = BUSAN_DOC["Q"] * ratio * PY
    rBQ = square(0.3, Z1 - 0.3, qa)
    rAQ = square(rA["x0"] + 0.3, Z1 - 0.3, qa)
    rCQ = square(W - 0.3 - math.sqrt(qa), rWH["z1"] - 0.3, qa)
    tB = round(BUSAN_RACKS["B"] / BUSAN_DOC["B"] * PY_B)
    tA = round(BUSAN_RACKS["A"] / BUSAN_DOC["A"] * PY_A)
    tC = round(BUSAN_RACKS["AMB"] / BUSAN_DOC["C-H"] * PY_C)
    nB, capB, zB = cold_block(b, "CHILLED", "B", rB, tB, Z0 + 1.0, 13.0)
    nA, capA, zA = cold_block(b, "FROZEN", "A", rA, tA, Z0 + 1.0, 13.0)
    nC, capC, alloc = amb_block(b, rC, tC, round(rC["z0"] + 0.5, 3))
    Z = b.zone
    Z("B", "CHILLED", "냉장 B", rB, round(PY_B, 1))
    Z("A", "FROZEN", "냉동 A", rA, round(PY_A, 1))
    Z("RCV", "RECEIVING", "입고·검수", rRCV, round(BUSAN_DOC["RCV"] * ratio, 1))
    Z("CA", "SPARE", "가운데 통로", rCA, round((rCA["x1"] - rCA["x0"]) * (rCA["z1"] - rCA["z0"]) / PY, 1))
    Z("C-H", "AMBIENT", "상온 C~H", rC, round(PY_C, 1))
    Z("WH", "INBOUND_STORE", "상온 입고창고", rWH, round((BUSAN_DOC["WH"] + BUSAN_DOC["OFF"]) * ratio, 1))
    Z("PK", "PACKING", "패킹", rPK, round(BUSAN_DOC["PK"] * ratio, 1))
    Z("BQ", "QUARANTINE", "격리", rBQ, round(BUSAN_DOC["Q"] * ratio, 1))
    Z("AQ", "QUARANTINE", "격리", rAQ, round(BUSAN_DOC["Q"] * ratio, 1))
    Z("CQ", "QUARANTINE", "격리", rCQ, round(BUSAN_DOC["Q"] * ratio, 1))
    Z("SHIP", "SHIPPING", "출하대기", rSHIP, round(BUSAN_DOC["SHIP"] * ratio, 1))
    Z("PARK", "PARKING", "라이더 대기(B1 주차장)", R(16.0, BD + 4.0, 28.0, BD + 7.0))
    bw = b.box_walls
    truck = (xm0 + 1.0, xm1 - 1.0)               # 북쪽 차량문(B1 주차장 쪽, 가정)
    out = (rSHIP["x0"], rSHIP["x1"])             # 남쪽 출하 문(패킹 → 출하대기)
    bw(R(0, 0, W, BD), doors=[("N", *truck), ("S", *out)])
    # 냉장: 북쪽 입고문 가운데, 남쪽 출고문 가운데 / 냉동: 북쪽 입고문 동쪽 끝(세로 통로·입고·검수 쪽), 남쪽 출고문 가운데
    b_mid, a_mid = (rB["x0"] + rB["x1"]) / 2, (rA["x0"] + rA["x1"]) / 2
    bw(rB, doors=[("N", b_mid - 1.2, b_mid + 1.2), ("S", b_mid - 1.2, b_mid + 1.2)])
    bw(rA, doors=[("N", rA["x1"] - 2.8, rA["x1"] - 0.4), ("S", a_mid - 1.2, a_mid + 1.2)])
    # 입고·검수: 북쪽 차량문, 서쪽 문(북쪽 통로·세로 통로로 — 저온), 남쪽 문(가운데 통로 — 상온)
    rcv_door = (round(rRCV["z1"] / 2 - 1.2, 2), round(rRCV["z1"] / 2 + 1.2, 2))   # 9차: 입고·검수 ↔ 상온 입고창고 바로
    bw(rRCV, doors=[("N", *truck), ("W", 0.2, min(NC + 1.2, rRCV["z1"] - 0.2)), ("S", xm0 + 0.6, xm1 - 0.6), ("E", *rcv_door)])
    # 상온 C~H·패킹·가운데 통로: 벽 없음(사용자). 입고창고: 서쪽 문 위(가운데 통로) · 아래(상온 쪽 보충)
    bw(rWH, doors=[("W", *rcv_door), ("W", rCA["z0"] + 0.2, rCA["z1"] - 0.2), ("W", rC["z0"] + 1.0, rC["z0"] + 4.0), ("S", xr0 + 0.6, W - 0.6)])
    for r in (rBQ, rAQ):
        bw(r, doors=[("E", r["z0"] + 0.4, r["z0"] + 1.4)])
    bw(rCQ, doors=[("W", rCQ["z0"] + 0.4, rCQ["z0"] + 1.4)])
    def cold_pallets(room, q, zr, mid):
        return pallet_grid(R(q["x1"] + 0.5, zr + 0.8, room["x1"] - 0.3, Z1 - 0.3), 1.2, axis="x",
                           skip=[R(mid - 1.4, Z1 - 1.6, mid + 1.4, Z1)])
    b.reserves("CHILLED", "BR", take(cold_pallets(rB, rBQ, zB, b_mid), scaled(4, ratio)))
    b.reserves("FROZEN", "AR", take(cold_pallets(rA, rAQ, zA, a_mid), scaled(4, ratio)))
    b.reserves("AMBIENT", "WR", take(pallet_grid(R(rWH["x0"] + 0.3, 0.5, rWH["x1"] - 0.2, rCQ["z0"] - 0.3), 1.8), scaled(24, ratio)))
    b.stations(rPK["x0"] + 0.6, round(rPK["z0"] + 0.6, 2), round(rPK["z1"] - 0.6, 2), math.ceil(scaled(8, ratio) / 2))
    b.ship_racks([round((rSHIP["x0"] + rSHIP["x1"]) / 2, 2)], round(rSHIP["z0"] + 0.5, 2))
    b.docks(xm0 + 0.4, 0.5, 11, max(1, scaled(36, ratio) // 11), pitch=round((wm - 0.8) / 10, 3))
    P = b.L["points"]
    P["truckDoor"] = {"x": round((truck[0] + truck[1]) / 2, 2), "z": 0.3}
    P["truckDock"] = {"x": round(xm1 - 1.2, 2), "z": round(rRCV["z1"] - 0.4, 2)}
    P["inboundIdle"] = [{"x": round(xm0 + 0.5 + 0.6 * i, 2), "z": round(rRCV["z1"] - 1.0 + 0.5 * j, 2)} for j in range(2) for i in range(4)]
    P["cartDepot"] = {"x": round(xm0 + 0.4, 2), "z": round(rCA["z0"] + 0.25, 2)}
    P["idle"] = [{"x": round(xv0 + 0.3 + 0.4 * j, 2), "z": round(Z0 + 4.0 + 0.7 * i, 2)} for j in range(2) for i in range(12)]
    P["packQueue"] = {"x": round(rPK["x0"] + 0.4, 2), "z": round(rPK["z0"] + 0.2, 2)}
    P["shipDrop"] = {"x": round(rSHIP["x0"] - 2.6, 2), "z": round(BD + 0.6, 2)}
    P["riderPickup"] = {"x": round(rSHIP["x1"] + 0.4, 2), "z": round(rSHIP["z1"] - 0.3, 2)}
    P["riderParking"] = [{"x": round(16.6 + 1.2 * i, 2), "z": round(BD + 6.0, 2)} for i in range(10)]
    P["riderEntry"] = {"x": 12.0, "z": round(BD + 7.0, 2)}
    b.L["drawing"] = {
        "name": "창원 MFC(설계안)", "drawWidth": W + 4, "yard": R(0, BD, 36, BD + 8),
        "yardLabel": "B1 주차장(남쪽 — 출하·라이더)",
        "title": "창원지점 MFC 설계안 — 라이크빌 지하 1층 282평 · 부산 진구점 평면 × 282/463",
        "labelZ": {"A": 14.5, "B": 14.5, "C-H": round(rC["z1"] - 0.6, 2), "RCV": round(rRCV["z1"] - 1.9, 2),
                   "CA": round(rCA["z1"] - 0.2, 2), "SHIP": round(rSHIP["z1"] - 0.3, 2)}, "doorLabels": [],
        "stationNote": "(번호, 3대 × 2줄)",
        "scaleNote": f"위 = 북(z=0) · 가로 36 m × 깊이 {BD} m = 282평 (+ B1 주차장 8 m) · x = 동쪽, z = 남쪽",
        "notes": ["창원시 성산구 용호동 73-2 라이크빌 B1", "건물 모양·문 위치는 가정(실측 도면 아님)",
                  "부산 진구점 새 평면과 같은 통로·문, 평수 × 282/463, 휴게·사무 없음",
                  "냉장·냉동 = 북쪽 입고문 + 남쪽 출고문(→ 패킹)", "설명: layout-changwon/README.md"]}
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
