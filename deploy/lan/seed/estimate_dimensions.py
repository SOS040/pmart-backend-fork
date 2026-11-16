"""LAN 시험 상품 치수 추정(2026-11-16, 사용자 "상품 치수 가짜로 넣을 수 있나" → A안).

입력: sku|상품명|보관유형|구성수 (MFC 재고 SKU 사본) + sku|무게g|용량ml|단위|구성수|출처|키 (상품 서비스).
출력: CSV sku,name,storage,weight_g,volume_ml,width_mm,depth_mm,height_mm,rule — 모두 ESTIMATED(실측 아님).

방법: 상품명 키워드로 모양(병·캔·팩·상자·봉지·막대·필기구 등)과 밀도를 고르고, 부피 = 용량(ml) 또는 무게 ÷ 밀도 → 모양 비율로 가로·세로·높이.
묶음(B)은 낱개 크기 × 구성 수를 격자로 쌓는다. 지어내는 값이라 실측 전까지만 쓴다.
"""
import csv, math, re, sys

# (키워드 정규식, 규칙 이름, 밀도 g/ml, 비율 w:d:h — 가장 긴 변 기준 정규화 전)
RULES = [
    (r"볼펜|연필|샤프|네임펜|형광펜|사인펜|매직|마커", "pen", None, None),
    (r"지우개", "eraser", 1.4, (3, 2, 1.2)),
    (r"생수|물\b|워터|음료|사이다|콜라|주스|에이드|소다|커피|우유|두유|요구르트|요거트|드링크|맥주|소주|와인|차\b|티\b", "bottle", 1.03, (1, 1, 3.2)),
    (r"캔|참치|햄|스팸|통조림", "can", 1.1, (1, 1, 1.1)),
    (r"라면|면\b|우동|국수|파스타", "noodle", 0.25, (2.2, 1.6, 1)),
    (r"과자|칩|스낵|팝콘|쿠키|크래커|비스킷", "snack", 0.12, (2.4, 1, 3)),
    (r"화장지|휴지|키친타월|물티슈|티슈", "tissue", 0.12, (1.6, 1, 1.4)),
    (r"세제|섬유유연제|샴푸|린스|바디워시|치약|칫솔|비누|핸드워시", "household", 1.0, (1.6, 1, 3)),
    (r"두부|계란|달걀|유부", "tray", 0.9, (1.6, 1.1, 1)),
    (r"아이스크림|냉동|만두|피자|돈까스|너겟|떡볶이", "frozen_box", 0.6, (2.5, 1.8, 1)),
    (r"고기|돼지|소고기|닭|삼겹|목살|생선|연어|새우|오징어", "meat_tray", 0.95, (2.2, 1.5, 1)),
    (r"과일|사과|배\b|바나나|딸기|포도|귤|오렌지|토마토|채소|양파|감자|당근|버섯|상추|배추", "produce", 0.6, (2, 1.5, 1.2)),
    (r"소스|케첩|마요|간장|고추장|된장|식초|오일|기름|참기름|드레싱|잼|꿀|버터|스프레드", "jar", 1.1, (1, 1, 2)),
    (r"쌀|밀가루|설탕|소금", "sack", 0.8, (2, 1, 3)),
]
DEFAULT = ("box", 0.5, (1.8, 1.2, 1))

def shape(name):
    for pat, rule, dens, ratio in RULES:
        if re.search(pat, name):
            return rule, dens, ratio
    return DEFAULT

def dims_from_volume(vol_ml, ratio):
    w, d, h = ratio
    unit = (vol_ml * 1000 / (w * d * h)) ** (1 / 3)  # mm
    return [max(10, round(x * unit)) for x in (w, d, h)]

def pack(single, n):
    """낱개 n 개를 거의 정사각 격자로 바닥에 깐다(한 층)."""
    if n <= 1:
        return single
    cols = math.ceil(math.sqrt(n)); rows = math.ceil(n / cols)
    w, d, h = single
    return [w * cols, d * rows, h]

def estimate(name, weight_g, volume_ml, unit_type, units):
    rule, dens, ratio = shape(name)
    n = units if unit_type == "B" and units > 1 else 1
    if rule == "pen":
        single = [150, 12, 12] if re.search(r"샤프|볼펜|연필", name) else [140, 18, 18]
        n2 = int(m.group(1)) if (m := re.search(r"(\d+)\s*개입", name)) else 1
        out = pack(single, max(n, n2))
        if max(n, n2) > 1:  # 필기구 묶음은 얇은 상자
            out = [single[0] + 10, min(out[1], 120), max(out[2], 25)]
        return out, rule
    # 용량(ml)은 낱개 기준(예 "125ml 4입" → 125), 무게는 묶음 전체
    per_unit_ml = volume_ml if volume_ml else (weight_g / n) / dens
    per_unit_ml = max(per_unit_ml, 5)
    single = dims_from_volume(per_unit_ml * 1.15, ratio)  # 포장 여유 15%
    return [min(x, 5000) for x in pack(single, n)], rule

def main(names_path, attrs_path, out_path):
    names = {r[0]: r for r in (l.rstrip("\n").split("|") for l in open(names_path, encoding="utf-8")) if len(r) >= 4}
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        w = csv.writer(f)
        w.writerow(["sku", "name", "storage", "weight_g", "volume_ml", "width_mm", "depth_mm", "height_mm", "rule"])
        for line in open(attrs_path, encoding="utf-8"):
            p = line.rstrip("\n").split("|")
            if len(p) < 5:
                continue
            sku, wg, vol, ut, units = p[0], int(p[1]), (int(p[2]) if p[2] else None), p[3], int(p[4])
            nm = names.get(sku, [sku, "", "", ""])
            dims, rule = estimate(nm[1], wg, vol, ut, units)
            width, depth, height = dims  # 진열 방향: 비율의 (가로, 깊이, 높이) 그대로
            w.writerow([sku, nm[1], nm[2], wg, vol or "", width, depth, height, rule])

if __name__ == "__main__":
    main(*sys.argv[1:4])
