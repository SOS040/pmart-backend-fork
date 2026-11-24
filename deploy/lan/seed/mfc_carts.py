"""LAN 시험 센터 MFC-BJG-01 이동 카트(장바구니)를 40대로 늘리고 카트 라벨을 다시 만든다(2026-11-17, 사용자 "40개까지").

지금 카트는 4대(CRT-A-01·A-02·C-01·F-01, 11-09 mfc_layout 이 만든 시험 데이터)라 동시에 피킹할 수 있는 사람이 4명뿐이었다.
목표(사용자 "응 진행해줘" — 칸 수 비율): 상온 A 20대, 냉장 C 12대, 냉동 F 8대. 이름 규칙 그대로 `CRT-<온도대>-NN`, 구역 CART,
위치는 기존 카트와 같은 카트 보관소 자리(도면 cartDepot). 있는 카트는 건너뛴다(다시 돌려도 안전).

라벨: `labels/cart-labels-MFC-BJG-01.html` — 카트마다 Code128(스캔 값 = 위치 코드)을 미리 그린 SVG 로 넣는다(인쇄 때 인터넷·스크립트 불필요).
QR 은 이 스크립트에 없다(QR 라이브러리 없음 — 필요하면 따로).

사용: python -I deploy/lan/seed/mfc_carts.py            # 계획만
      python -I deploy/lan/seed/mfc_carts.py --apply    # 카트 추가 + 라벨
      python -I deploy/lan/seed/mfc_carts.py --labels   # 라벨만 다시
"""
import html, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
LABELS = os.path.join(HERE, "labels", "cart-labels-MFC-BJG-01.html")
# 2026-11-17 사용자 "일단 상온으로 해줘. 나중에 온도대없음으로 바꿀게": 카트는 온도대와 상관없이 쓴다(서버는 카트 온도대를 보지 않음 —
# requireCart 는 구역 CART 만). 위치 코드 규칙(ADR-0042 `{접두}-{A|C|F}-{번호}`)상 글자 하나는 필요해 전부 상온 A. 냉장·냉동 카트는 닫는다(재고 있으면 열어 둠).
TARGET = {"A": ("AMBIENT", 40)}
TZ_KO = {"AMBIENT": "상온", "CHILLED": "냉장", "FROZEN": "냉동"}

# Code128 막대 폭 표(값 0~106) — labels/locations/*.html 의 그리기 코드와 같다
P = ["212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213", "221312", "231212",
     "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
     "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321", "232121", "111323", "131123", "131321",
     "112313", "132113", "132311", "211313", "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
     "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
     "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
     "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111", "111242", "121142", "121241", "114212",
     "124112", "124211", "411212", "421112", "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
     "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412", "211214", "211232", "2331112"]


def code128_svg(text):
    """Code128 B(시작 104, 검사 값, 정지 106) → SVG. 글자는 ASCII 32~126 만."""
    codes = [104] + [ord(ch) - 32 for ch in text]
    chk = 104 + sum(i * c for i, c in enumerate(codes[1:], start=1))
    codes += [chk % 103, 106]
    widths = "".join(P[c] for c in codes)
    x, rects = 10, []
    for i, w in enumerate(widths):
        w = int(w)
        if i % 2 == 0:
            rects.append(f'<rect x="{x}" y="0" width="{w}" height="40"/>')
        x += w
    return f'<svg viewBox="0 0 {x + 10} 40" preserveAspectRatio="none"><g fill="#000">{"".join(rects)}</g></svg>'


def wanted():
    return [(f"CRT-{t}-{n:02d}", tz) for t, (tz, count) in TARGET.items() for n in range(1, count + 1)]


def write_labels(codes):
    cards = []
    for code, tz in codes:
        cards.append(f'<div class="label"><div class="top"><span class="kind">이동 카트</span>'
                     f'<span class="zone z-ANY">온도 구분 없음</span></div>{code128_svg(code)}'
                     f'<div class="code">{html.escape(code)}</div><div class="foot">MFC-BJG-01 · 구역 CART</div></div>')
    counts = "온도대 구분 없이 아무 카트나 — 상온·냉장·냉동 상품을 한 카트에(한 출고 = 한 카트)"
    page = f"""<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>카트 바코드 라벨 — MFC-BJG-01</title>
<style>
@page {{ size: A4; margin: 12mm; }}
body {{ font-family: "Malgun Gothic", sans-serif; margin: 0; background: #fff; color: #000; }}
h1 {{ font-size: 14px; margin: 0 0 8px; }}
.note {{ font-size: 11px; color: #444; margin-bottom: 10px; }}
.grid {{ display: grid; grid-template-columns: repeat(2, 1fr); gap: 6mm; }}
.label {{ border: 1px dashed #888; padding: 5mm; text-align: center; break-inside: avoid; }}
.top {{ display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 3mm; }}
.zone {{ font-weight: 700; padding: 1px 8px; border-radius: 4px; color:#fff; }}
.z-ANY {{ background:#444; }} .z-AMBIENT {{ background:#8a5a00; }} .z-CHILLED {{ background:#0b5cad; }} .z-FROZEN {{ background:#3b2a8f; }}
svg {{ width: 100%; height: 24mm; }}
.code {{ font: 700 24px/1.2 Consolas, monospace; letter-spacing: 2px; margin-top: 2mm; }}
.foot {{ font-size: 10px; color: #555; margin-top: 1mm; }}
@media print {{ .note {{ display:none; }} }}
</style></head><body>
<h1>MFC-BJG-01 이동 카트 라벨 (Code128) — {len(codes)}대</h1>
<div class="note">2026-11-17 · {counts}. 브라우저에서 인쇄(Ctrl+P, 배율 100%, A4 한 장에 10장). 바코드 값은 위치 코드 그대로 —
PDA 가 찍으면 mfc-location 의 카트 위치(구역 CART)로 읽는다. 다시 만들기: python -I deploy/lan/seed/mfc_carts.py --labels</div>
<div class="grid">{"".join(cards)}</div>
</body></html>
"""
    with open(LABELS, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(page)
    print(f"라벨 {len(codes)}장 → {os.path.relpath(LABELS)}")


def main(apply, labels_only):
    want = wanted()
    if labels_only:
        write_labels(want)
        return
    s = v1.Session()
    cur = v1.current_locations(s)
    depot = cur.get("CRT-A-01")
    if depot is None or not depot.get("position"):
        raise SystemExit("기준 카트 CRT-A-01(위치 좌표)이 없다 — mfc_layout 을 먼저")
    missing = [(c, tz) for c, tz in want if c not in cur]
    print(f"목표 {len(want)}대, 있음 {len(want) - len(missing)}, 추가할 것 {len(missing)}")
    if not apply:
        return
    ok = failed = 0
    for code, tz in missing:
        body = {"mfcId": v1.MFC, "locationCode": code, "zone": "CART", "temperatureZone": tz,
                "highTurnover": False, "position": depot["position"]}
        st, b = v1.http("POST", f"{v1.GW}/locations", s.sup(), body)
        if st in (200, 201):
            ok += 1
        else:
            failed += 1
            print("  실패", code, st, b.get("code"), (b.get("detail") or "")[:120])
        time.sleep(0.05)
    print(f"추가 {ok}, 실패 {failed}")
    after = v1.current_locations(s)
    active = [c for c, _ in want if (after.get(c) or {}).get("state") == "ACTIVE"]
    print(f"활성 카트 {len(active)}/{len(want)}")
    # 목표 밖 카트(옛 냉장·냉동 CRT-C/F) 닫기 — 재고가 남은 카트는 열어 둔다
    held = {r["locationCode"] for r in v1.snapshot() if r["qty"] > 0 and r["locationCode"].startswith("CRT-")}
    wanted_codes = {c for c, _ in want}
    closed = kept = 0
    for code, cur in sorted(after.items()):
        if not code.startswith("CRT-") or code in wanted_codes or cur.get("state") != "ACTIVE":
            continue
        if code in held:
            kept += 1
            print("  재고가 있어 열어 둠:", code)
            continue
        st, b = v1.http("POST", f"{v1.GW}/locations/{cur['locationId']}/state-changes", s.sup(),
                        {"targetState": "CLOSED", "reason": "카트 온도대 구분 없앰 — 상온 코드 40대로 통일(2026-11-17)"})
        closed += st in (200, 202)
        if st not in (200, 202):
            print("  닫기 실패", code, st, b.get("code"), b.get("detail"))
        time.sleep(0.05)
    print(f"목표 밖 카트 닫음 {closed}, 열어 둠 {kept}")
    write_labels(want)


if __name__ == "__main__":
    main("--apply" in sys.argv, "--labels" in sys.argv)
