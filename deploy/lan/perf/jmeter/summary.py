"""results.jtl(CSV) → 요청별 건수·오류율·p50·p95·상태 코드, 멈춤 기준 판정(오류 > 1% 또는 전체 p95 > 2초면 종료 코드 1).

422(업무 거절 — 품절 SKU_NOT_SALEABLE·한 상품 10개 넘음 등, 검색 결과 대표 SKU 를 담을 때 남)는 오류가 아니라 따로 센다(2026-11-27).
"""

import csv
import sys
from collections import Counter, defaultdict


def pct(v, p):
    if not v:
        return 0
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * p))]


def main() -> int:
    rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
    by = defaultdict(list)
    codes = defaultdict(Counter)
    err = Counter()
    biz = Counter()
    for r in rows:
        k = r["label"]
        by[k].append(int(r["elapsed"]))
        codes[k][r["responseCode"]] += 1
        if r["success"] != "true":
            if r["responseCode"] == "422":
                biz[k] += 1
            else:
                err[k] += 1
    allv = [int(r["elapsed"]) for r in rows]
    total_err = sum(err.values())
    print(f"{'요청':40} {'건수':>6} {'오류%':>6} {'p50':>6} {'p95':>6}  상태")
    for k in sorted(by):
        v = by[k]
        print(f"{k[:40]:40} {len(v):6d} {100 * err[k] / len(v):6.1f} {pct(v, .5):6d} {pct(v, .95):6d}  {dict(codes[k].most_common(4))}")
    rate = 100 * total_err / max(1, len(rows))
    p95 = pct(allv, .95)
    secs = (int(rows[-1]["timeStamp"]) - int(rows[0]["timeStamp"])) / 1000 if rows else 0
    print(f"전체 {len(rows)}건 · 처리량 {len(rows) / max(1, secs):.1f}/s · 오류 {rate:.2f}% · p95 {p95}ms"
          f" · 업무 거절(422, 오류 아님) {sum(biz.values())}건")
    return 1 if rate > 1 or p95 > 2000 else 0


if __name__ == "__main__":
    sys.exit(main())
