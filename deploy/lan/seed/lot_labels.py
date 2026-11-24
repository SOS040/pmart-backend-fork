"""MFC-BJG-01 LOT 바코드 라벨(GS1-128)을 지금 LAN 재고로 만든다(2026-11-17, IV-4 — PC 1 몫).

값은 서버가 주는 그대로: mfc-inventory `GET /mfcs/{mfcId}/lot-labels?sku=` 의 `gs1`(괄호 꼴 `(01)GTIN-14(17)YYMMDD(10)lotNo`,
기한 없는 LOT 은 `(17)` 생략). 고정 길이(01·17)가 앞, 가변(10)이 끝이라 FNC1 구분자 없이 한 줄로 이어진다.
바코드 = Code 128 + 첫 FNC1(GS1-128). 심볼은 여기서 계산해 넣고(브라우저는 막대만 그림), 만들 때 두 가지를 확인한다:
  1) 계산한 심볼을 다시 읽으면(검사 숫자 포함) 같은 GS1 원문이 나오는지 — 전부.
  2) 스캐너가 줄 원문(`]C1` + 원문)으로 `GET /mfcs/{mfcId}/lot-resolutions?code=` 를 부르면 같은 LOT 이 나오는지 — 표본(--check N, 기본 20).
위치는 재고 스냅샷(LOT 마다 한 칸)으로, 위치 라벨과 같게 온도대-구역(예 A-C1)마다 한 파일. 시험용이라 LOT 마다 1장
(원칙은 "라벨 1장 = 그 SKU 1단위" — 낱개마다 붙일 땐 같은 라벨을 수량만큼 인쇄).
사용: python -I deploy/lan/seed/lot_labels.py [--check N]
"""
import collections, json, os, random, sys, urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "labels", "lots")
PER_PAGE = 16
TZ = {"AMBIENT": "상온", "CHILLED": "냉장", "FROZEN": "냉동"}

# Code 128 막대 폭(값 0~106). 위치 라벨(locations/*.html)의 표와 같다.
P = ["212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213", "221312", "231212",
     "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
     "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321", "232121", "111323", "131123", "131321",
     "112313", "132113", "132311", "211313", "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
     "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
     "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
     "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111", "111242", "121142", "121241", "114212",
     "124112", "124211", "411212", "421112", "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
     "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412", "211214", "211232", "2331112"]
START_C, FNC1, CODE_B, CODE_C, STOP = 105, 102, 100, 99, 106


def element_string(gs1):
    """괄호 꼴 → 바코드에 넣는 원문(괄호 뺌). 가변 (10) 이 끝이라 구분자가 필요 없다 — 아니면 멈춘다."""
    parts = gs1.replace(")", "(").split("(")[1:]
    ais = parts[0::2]
    if not ais or ais[-1] != "10" or "10" in ais[:-1]:
        raise SystemExit(f"가변 AI(10)가 끝이 아닌 GS1 — 구분자 처리 필요: {gs1}")
    return "".join(a + v for a, v in zip(ais, parts[1::2]))


def encode(data):
    """GS1-128 심볼 값 목록(시작·FNC1·데이터·검사·끝). 숫자 둘은 C, 그 밖은 B, 숫자 4개 이상이면 다시 C."""
    cw, mode, i, n = [START_C, FNC1], "C", 0, len(data)
    while i < n:
        if mode == "C":
            if i + 1 < n and data[i].isdigit() and data[i + 1].isdigit():
                cw.append(int(data[i:i + 2]))
                i += 2
            else:
                cw.append(CODE_B)
                mode = "B"
        else:
            run = 0
            while i + run < n and data[i + run].isdigit():
                run += 1
            if run >= 4:
                if run % 2:
                    cw.append(ord(data[i]) - 32)
                    i += 1
                cw.append(CODE_C)
                mode = "C"
            else:
                ch = data[i]
                if not 32 <= ord(ch) <= 126:
                    raise SystemExit(f"Code B 로 못 넣는 글자: {ch!r}")
                cw.append(ord(ch) - 32)
                i += 1
    check = (cw[0] + sum(k * v for k, v in enumerate(cw) if k > 0)) % 103
    return cw + [check, STOP]


def decode(cw):
    """막대 폭으로 바꿨다가 다시 읽어 검사 숫자·FNC1·원문을 확인한다(encode 시험)."""
    bars = "".join(P[x] for x in cw)
    rev = {p: k for k, p in enumerate(P)}
    vals = [rev[bars[k:k + 6]] for k in range(0, len(bars) - 7, 6)] + [rev[bars[-7:]]]
    if vals[0] != START_C or vals[1] != FNC1 or vals[-1] != STOP:
        raise ValueError("시작 C·FNC1·끝이 아님")
    body, check = vals[:-2], vals[-2]
    if (body[0] + sum(k * v for k, v in enumerate(body) if k > 0)) % 103 != check:
        raise ValueError("검사 숫자 틀림")
    out, mode = [], "C"
    for v in body[2:]:
        if v == CODE_B and mode == "C" or v == CODE_C and mode == "B":
            mode = "B" if v == CODE_B else "C"
        elif mode == "C":
            out.append(f"{v:02d}")
        else:
            out.append(chr(v + 32))
    return "".join(out)


def fetch_labels(s, skus):
    out = {}
    for n, sku in enumerate(sorted(skus), 1):
        st, b = v1.http("GET", f"{v1.GW}/mfcs/{v1.MFC}/lot-labels?sku={urllib.parse.quote(sku)}", s.sup())
        if st != 200:
            raise SystemExit(f"lot-labels {sku} HTTP {st} {b}")
        for it in b["items"]:
            out[it["lotId"]] = it
        if n % 100 == 0:
            print(f"  상품 {n}/{len(skus)}")
    return out


def page(title, rows):
    data = json.dumps(rows, ensure_ascii=False, separators=(",", ":"))
    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>{title}</title><style>@page{{size:A4;margin:8mm}}
body{{font-family:"Malgun Gothic",sans-serif;margin:0;background:#fff;color:#000}}h1{{font-size:13px;margin:0 0 4px}}.n{{font-size:10px;color:#444;margin-bottom:6px}}
#g{{display:grid;grid-template-columns:repeat(2,1fr);gap:2mm}}
.l{{border:1px dashed #999;padding:1.5mm 2.5mm;height:33mm;box-sizing:border-box;break-inside:avoid;display:flex;flex-direction:column}}
.hd{{display:flex;justify-content:space-between;font-size:9px;gap:4px}}.hd b{{white-space:nowrap}}
.t-AMBIENT .tz{{color:#8a5a00}}.t-CHILLED .tz{{color:#0b5cad}}.t-FROZEN .tz{{color:#3b2a8f}}.tz{{font-weight:700}}
.pn{{font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}svg{{width:100%;height:12mm;margin:0.8mm 0}}
.hr{{font:9px Consolas,monospace;text-align:center;letter-spacing:.3px}}.ft{{display:flex;justify-content:space-between;align-items:baseline;font-size:9px}}
.ln{{font:700 13px Consolas,monospace;letter-spacing:1px}}
@media print{{.n,a{{display:none}}}}</style></head><body><a href="index.html">← 목록</a><h1>{title}</h1>
<div class="n">GS1-128 · (01) 상품번호 GTIN-14 · (17) 소비기한 YYMMDD · (10) LOT 번호 — PDA 는 (10) 을 피킹 지시 LOT 번호와 대조. 인쇄 Ctrl+P 배율 100%, A4 한 장 {PER_PAGE}장. 시험용으로 LOT 마다 1장.</div>
<div id="g"></div><script>
const D={data};
const P={json.dumps(P, separators=(",", ":"))};
function svg(c){{const m=c.map(x=>P[x]).join("");let x=10,r="";for(let i=0;i<m.length;i++){{const w=+m[i];if(i%2==0)r+=`<rect x="${{x}}" y="0" width="${{w}}" height="40"/>`;x+=w;}}
return `<svg viewBox="0 0 ${{x+10}} 40" preserveAspectRatio="none"><g fill="#000">${{r}}</g></svg>`;}}
const TZ={{AMBIENT:"상온",CHILLED:"냉장",FROZEN:"냉동"}};
const e=s=>String(s??"").replace(/[&<>"]/g,ch=>({{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}}[ch]));
document.getElementById("g").innerHTML=D.map(d=>`<div class="l t-${{d.t}}"><div class="hd"><span><span class="tz">${{TZ[d.t]||e(d.t)}}</span> · ${{e(d.loc)}}</span><b>${{e(d.sku)}}</b></div>
<div class="pn">${{e(d.name)}}</div>${{svg(d.k)}}<div class="hr">${{e(d.gs1)}}</div>
<div class="ft"><span>${{d.exp?"소비기한 "+e(d.exp):"기한 없음"}} · 수량 ${{d.q}}</span><span class="ln">${{e(d.lotNo)}}</span></div></div>`).join("");
</script></body></html>
"""


def main(check_n):
    s = v1.Session()
    rows = [r for r in v1.snapshot() if r["qty"] > 0]
    locs = v1.current_locations(s)
    labels = fetch_labels(s, {r["sku"] for r in rows})
    groups, skipped, ids = collections.defaultdict(list), [], []
    for r in rows:
        lab = labels.get(r["lotId"])
        if not lab or not lab.get("gs1"):
            skipped.append((r["sku"], r["lotId"], "라벨 없음" if not lab else "gs1 없음(숫자 바코드 없음)"))
            continue
        data = element_string(lab["gs1"])
        cw = encode(data)
        if decode(cw) != data:
            raise SystemExit(f"바코드 다시 읽기 불일치: {lab['gs1']}")
        code = r["locationCode"]
        p = code.split("-")
        key = f"{p[0]}-{p[1]}" if len(p) == 5 else "기타"
        loc = locs.get(code, {})
        groups[key].append({"loc": code, "t": loc.get("temperatureZone"), "sku": r["sku"], "name": lab.get("productName") or "",
                            "gs1": lab["gs1"], "lotNo": lab["lotNo"], "exp": lab.get("expiryDate"), "q": r["qty"], "k": cw})
        ids.append((data, r["lotId"]))
    # 스캐너가 줄 원문(]C1 + 원문)으로 서버 LOT 찾기 — 표본
    random.seed(17)
    bad = 0
    for data, lot_id in random.sample(ids, min(check_n, len(ids))):
        st, b = v1.http("GET", f"{v1.GW}/mfcs/{v1.MFC}/lot-resolutions?code={urllib.parse.quote(']C1' + data)}", s.sup())
        if st != 200 or b.get("lotId") != lot_id:
            bad += 1
            print(f"  LOT 찾기 불일치: {data} → HTTP {st} {b.get('lotId') or b}")
    if bad:
        raise SystemExit(f"서버 LOT 찾기 불일치 {bad}건 — 파일을 쓰지 않음")
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.endswith(".html"):
            os.remove(os.path.join(OUT, f))
    index_rows = []
    for key in sorted(groups, key=lambda k: (k == "기타", k)):
        d = sorted(groups[key], key=lambda x: (x["loc"], x["exp"] or "9999", x["lotNo"]))
        title = f"MFC-BJG-01 LOT 라벨 — {key} ({len(d)}개 LOT)"
        with open(os.path.join(OUT, key + ".html"), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(page(title, d))
        temps = sorted({TZ.get(x["t"], x["t"] or "?") for x in d})
        index_rows.append(f'<tr><td><a href="{key}.html">{key}</a></td><td>{"·".join(temps)}</td><td>{len(d)}</td>'
                          f'<td>{-(-len(d) // PER_PAGE)}</td></tr>')
    total = sum(len(v) for v in groups.values())
    skip_html = "".join(f"<li>{a} {b} — {c}</li>" for a, b, c in skipped)
    index = ('<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>MFC-BJG-01 LOT 라벨 목록</title><style>body{font-family:"Malgun Gothic",sans-serif;'
             'margin:16px;background:#fff;color:#000}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px 10px;font-size:13px}</style></head><body>'
             '<h1 style="font-size:16px">MFC-BJG-01 LOT 바코드 라벨 (GS1-128, 2026-11-17)</h1>'
             f'<p style="font-size:12px">지금 보유가 있는 LOT {total}개 — 위치 라벨(../locations/)과 같은 온도대-구역마다 한 파일, 칸 순서. '
             '값 = mfc-inventory lot-labels 의 gs1 그대로. 시험용으로 LOT 마다 1장.</p>'
             '<table><tr><th>파일</th><th>온도대</th><th>LOT 수</th><th>A4 장 수</th></tr>' + "".join(index_rows) + "</table>"
             + (f"<p style='font-size:12px'>라벨을 못 만든 LOT {len(skipped)}개:</p><ul style='font-size:12px'>{skip_html}</ul>" if skipped else "")
             + "</body></html>\n")
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(index)
    print(f"파일 {len(groups)}, LOT {total}, 못 만든 LOT {len(skipped)}, A4 약 {sum(-(-len(v) // PER_PAGE) for v in groups.values())}장, "
          f"다시 읽기 {total}/{total} 일치, 서버 LOT 찾기 표본 {min(check_n, len(ids))}건 일치")


if __name__ == "__main__":
    n = 20
    if "--check" in sys.argv:
        n = int(sys.argv[sys.argv.index("--check") + 1])
    main(n)
