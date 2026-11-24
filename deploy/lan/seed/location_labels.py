"""MFC-BJG-01 위치 바코드 라벨(Code128, 스캔 값 = 위치 코드)을 지금 LAN 활성 위치로 다시 만든다.

2026-11-16 칸 구조를 문서 설계로 바꾼 뒤(mfc_layout_v2) 처음 만듦. 모양·바코드 그리는 코드는 첫 라벨(7dac7021)의
A-C1.html 을 틀로 그대로 쓴다(데이터 D 만 바꿈). 카트 라벨(cart-labels-*.html)은 건드리지 않는다.
사용: python -I deploy/lan/seed/location_labels.py
"""
import collections, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mfc_layout as v1  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "labels", "locations")
TZ = {"AMBIENT": "상온", "CHILLED": "냉장", "FROZEN": "냉동"}
PER_PAGE = 30


def main():
    tpl = open(os.path.join(OUT, "A-C1.html"), encoding="utf-8").read()
    i = tpl.index("const D=")
    j = tpl.index("];", i) + 2
    head, tail = tpl[:i], tpl[j:]
    old_title = head[head.index("<title>") + 7:head.index("</title>")]
    locs = [x for x in v1.current_locations(v1.Session()).values()
            if x.get("state") == "ACTIVE" and not x["locationCode"].startswith("CRT-")]
    groups = collections.defaultdict(list)
    for x in locs:
        c = x["locationCode"]
        p = c.split("-")
        key = f"{p[0]}-{p[1]}" if x.get("zone") == "STORAGE" and len(p) == 5 else "기타"
        groups[key].append({"c": c, "z": x.get("zone"), "t": x.get("temperatureZone"), "h": bool(x.get("highTurnover"))})
    for f in os.listdir(OUT):
        if f.endswith(".html") and f != "A-C1.html":
            os.remove(os.path.join(OUT, f))
    rows = []
    for key in sorted(groups, key=lambda k: (k == "기타", k)):
        d = sorted(groups[key], key=lambda r: r["c"])
        title = f"MFC-BJG-01 위치 라벨 — {key} ({len(d)}곳)"
        h = head.replace(old_title, title).replace(f"<h1>{old_title}</h1>", f"<h1>{title}</h1>")
        page = h + "const D=" + json.dumps(d, ensure_ascii=False, separators=(",", ":")) + ";" + tail
        with open(os.path.join(OUT, key + ".html"), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(page)
        temps = sorted({TZ.get(r["t"], r["t"]) for r in d})
        rows.append(f'<tr><td><a href="{key}.html">{key}</a></td><td>{"·".join(temps)}</td><td>{len(d)}</td><td>{-(-len(d) // PER_PAGE)}</td></tr>')
    total = sum(len(v) for v in groups.values())
    index = ('<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>MFC-BJG-01 위치 라벨 목록</title><style>body{font-family:"Malgun Gothic",sans-serif;'
             'margin:16px;background:#fff;color:#000}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px 10px;font-size:13px}</style></head><body>'
             '<h1 style="font-size:16px">MFC-BJG-01 위치 바코드 라벨 (2026-11-16, 문서 칸 설계)</h1>'
             f'<p style="font-size:12px">활성 위치 {total}곳(카트는 ../cart-labels-MFC-BJG-01.html). 선반은 온도대-구역(예 A-C1)마다 한 파일, '
             '포장대·출하대기·격리·수령·회수는 "기타". 바코드 값 = 위치 코드 그대로(Code128).</p>'
             '<table><tr><th>파일</th><th>온도대</th><th>위치 수</th><th>A4 장 수</th></tr>' + "".join(rows) + "</table></body></html>\n")
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(index)
    print(f"파일 {len(groups)}, 위치 {total}, A4 약 {sum(-(-len(v) // PER_PAGE) for v in groups.values())}장")


if __name__ == "__main__":
    main()
