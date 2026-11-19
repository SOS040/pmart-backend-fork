"""LAN 상품 내용량(netContent) 일괄 채우기 — 옵션 표시 문구(capacityLabel)에서 읽는다(2026-11-19, BR-19, 사용자 "(나)로 해줘").

경로(정식 승인): 상품 정보 수정 초안(POST /products/{id}/edit-drafts — 현재 상태를 다 옮겨 담음: 바코드·치수·소비기한 보장·물류·필터 옵션)
→ 초안 input 의 skus[0].netContent 만 더해 PUT → 초안 제출 → 행을 모아 **변경 요청 하나**(PRODUCT) → 제출. 승인은 사람이 한다(이 스크립트는 승인하지 않는다).
(seed 재적재 묶음은 필터 옵션·물류를 싣지 않아 재승인 때 지워지므로 쓰지 않는다.)

읽는 문구(공백 무시, 대소문자 무시): "500g" "1kg" "1.5L" "180ml" — 하나의 양, "200ml×6" "500g x 2입" — 양×개수(판매 단위 전체 = 곱).
그 밖(범위·여러 양·개수만·옵션 여러 개·SKU 를 모르는 옵션)은 건너뛰고 사유를 남긴다. 이미 내용량이 있으면 건너뛴다.

사용(PC 1):
  python -I deploy/lan/seed/fill_net_content.py                 # 목록만 — deploy/lan/seed/net-content-2026-11-19.csv
  python -I deploy/lan/seed/fill_net_content.py --apply         # 그 목록대로 초안·제출·변경 요청(승인 대기)
수정 시작은 MD 역할만(pmart.product.edit-role) — 시험 계정 claude-cs 에 잠시 MD 를 주고 끝나면 CS_AGENT 로 돌린다.
토큰·비밀번호는 출력하지 않는다.
"""
import csv
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "checks"))
from staff_auth import staff_token, GW  # noqa: E402

OUT = os.path.join(HERE, "net-content-2026-11-19.csv")
UNITS = {"g": ("G", 1), "kg": ("G", 1000), "ml": ("ML", 1), "l": ("ML", 1000)}
SINGLE = re.compile(r"^(\d+(?:\.\d+)?)(kg|g|ml|l)$")
MULTI = re.compile(r"^(\d+(?:\.\d+)?)(kg|g|ml|l)[x×*](\d+)(입|개|팩|병|캔|봉|ea)?$")
AMOUNT = r"(\d+(?:\.\d+)?)\s*(kg|g|ml|l)(?![a-z])"
# 이름으로 문구(낱개 양일 수 있음)를 판매 단위 전체로 고친다 — 확실한 꼴만, 애매하면 건너뛴다(2026-11-19)
NAME_MULTI = re.compile(AMOUNT + r"\s*[x×*]\s*(\d+)", re.IGNORECASE)  # 60g×6개입, 5g×9봉 → 낱개×개수
PACK_MULTI = re.compile(r"(\d+)\s*입\s*[x×*]\s*" + AMOUNT, re.IGNORECASE)  # (3입×60ml)
AFTER_COUNT = re.compile(AMOUNT + r"\s*(\d+)\s*(입|개|캔|봉|병|팩)(?!입)", re.IGNORECASE)  # 85g 4입, 350ml 6캔 → 낱개
AFTER_GAEIP = re.compile(AMOUNT + r"\s*(\d+)\s*개입", re.IGNORECASE)  # 200g 3개입 — 낱개인지 전체인지 모름
BUNDLE = re.compile(r"\[(\d+)개\s*묶음\]")  # [2개 묶음] — 같은 상품 N개


def base(a, u):
    unit, mul = UNITS[u.lower()]
    return float(a) * mul, unit


def decide(name, label, unit_type, units):
    """문구 양 label=(amount, unit) → (amount, note) 또는 (None, 사유)."""
    amount, unit = label
    in_name = {base(a, u) for a, u in re.findall(AMOUNT, name, re.IGNORECASE)}
    k = None
    note = ""
    for rx, order in ((NAME_MULTI, "ak"), (PACK_MULTI, "ka")):
        m = rx.search(name)
        if m:
            a, u, n = (m.group(1), m.group(2), m.group(3)) if order == "ak" else (m.group(2), m.group(3), m.group(1))
            if base(a, u) == (amount, unit):
                k, note = int(n), f"문구는 낱개 — 이름 ×{n}"
            elif base(a, u)[0] * int(n) == amount:
                k, note = 1, "문구가 전체(이름 낱개×개수와 같음)"
            break
    if k is None:
        m = AFTER_GAEIP.search(name)
        if m and base(m.group(1), m.group(2)) == (amount, unit) and int(m.group(3)) > 1:
            return None, "이름 'N개입' — 낱개 양인지 전체인지 모름"
        m = AFTER_COUNT.search(name)
        if m and base(m.group(1), m.group(2)) == (amount, unit) and int(m.group(3)) > 1:
            k, note = int(m.group(3)), f"문구는 낱개 — 이름 {m.group(3)}{m.group(4)}"
    b = BUNDLE.search(name)
    if b:
        if k not in (None, 1):
            return None, "묶음 안에 또 개수 — 확인 필요"
        k, note = int(b.group(1)), f"{b.group(1)}개 묶음 — 문구는 낱개"
    if k is None:
        if in_name and (amount, unit) not in in_name:
            return None, "이름의 양과 문구가 다름"
        if len(in_name) > 1:
            return None, "이름에 양이 여럿 — 확인 필요"
        k = 1
    if unit_type == "B" and k > 1 and units != k:
        return None, f"개수 {k} 와 구성 낱개 수 {units} 가 다름"
    total = amount * k
    if total != int(total) or not 1 <= total <= 1_000_000:
        return None, "정수 g·mL 아님/범위 밖"
    return int(total), note


def tok(name, roles=None):
    t = staff_token(name, roles, []) if roles else staff_token(name)
    return t[0] if isinstance(t, tuple) else t


def call(method, path, token=None, body=None, version=None):
    headers = {"X-Source-System": "pc1-net-content", "Idempotency-Key": str(uuid.uuid4())}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if version is not None:
        headers["X-Expected-Version"] = str(version)
    for attempt in range(15):
        req = urllib.request.Request(GW + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read()
            if e.code in (429, 503) and attempt < 14:
                time.sleep(2 if e.code == 429 else 5)  # 429 = 게이트웨이 처리율 제한
                continue
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"raw": raw[:300].decode("utf-8", "replace")}


def parse(label):
    """(amount, unit) 또는 (None, 사유)."""
    if not label:
        return None, "문구 없음"
    s = re.sub(r"\s+", "", label).lower()
    m = SINGLE.match(s)
    n = 1
    if not m:
        m = MULTI.match(s)
        if not m:
            return None, "읽을 수 없는 문구"
        n = int(m.group(3))
    unit, mul = UNITS[m.group(2)]
    amount = float(m.group(1)) * mul * n
    if amount != int(amount) or not 1 <= amount <= 1_000_000:
        return None, "정수 g·mL 아님/범위 밖"
    return (int(amount), unit), None


def candidates(admin):
    ids, cursor = [], None
    while True:
        st, page = call("GET", "/products?size=100" + (f"&cursor={cursor}" if cursor else ""), admin)
        if st != 200:
            raise SystemExit(f"상품 목록 HTTP {st}")
        ids += [p["productId"] for p in page.get("items", [])]
        cursor = (page.get("page") or {}).get("nextCursor")
        if not cursor:
            break
    rows = []
    for i in range(0, len(ids), 50):
        chunk = ids[i:i + 50]
        st, cards = call("GET", "/product-contents?productIds=" + ",".join(chunk))
        if st != 200:
            raise SystemExit(f"카드 HTTP {st}")
        got = {c["productId"]: c for c in cards.get("items", [])}
        for pid in chunk:
            c = got.get(pid)
            row = {"productId": pid, "name": (c or {}).get("name", ""), "sku": "", "capacityLabel": "",
                   "amount": "", "unit": "", "skip": "", "note": ""}
            opts = (c or {}).get("options") or []
            if c is None:
                row["skip"] = "콘텐츠 없음"
            elif len(opts) != 1 or not opts[0].get("sku"):
                row["skip"] = "옵션이 하나가 아님/SKU 모름"
            else:
                o = opts[0]
                row["sku"], row["capacityLabel"] = o["sku"], o.get("capacityLabel") or ""
                if o.get("netContent"):
                    row["skip"] = "이미 내용량 있음"
                else:
                    v, why = parse(row["capacityLabel"])
                    if v:
                        total, note = decide(row["name"], v, o.get("unitType"), o.get("unitsPerSku"))
                        if total is None:
                            row["skip"] = note
                        else:
                            row["amount"], row["unit"], row["note"] = total, v[1], note
                    else:
                        row["skip"] = why
            rows.append(row)
    return rows


def apply(rows, admin):
    md = tok("claude-cs", ["MD"])
    lines, base, failed = [], None, []
    try:
        for i, r in enumerate(x for x in rows if not x["skip"]):
            st, d = call("POST", f"/products/{r['productId']}/edit-drafts", md)
            if st != 201:
                failed.append((r["productId"], f"수정 초안 HTTP {st} {d.get('code', '')}"))
                continue
            inp = d["input"]
            inp["skus"][0]["netContent"] = {"amount": int(r["amount"]), "unit": r["unit"]}
            st, d2 = call("PUT", f"/product-drafts/{d['draftId']}", admin, inp, version=d.get("version"))
            if st != 200 or d2.get("validationState") != "VALID":
                failed.append((r["productId"], f"PUT HTTP {st} {d2.get('validationState')} "
                               + json.dumps(d2.get("problems") or d2.get("code"), ensure_ascii=False)[:160]))
                continue
            st, sub = call("POST", f"/product-drafts/{d['draftId']}/submissions", admin)
            if st not in (200, 201):
                failed.append((r["productId"], f"제출 HTTP {st} {sub.get('code', '')}"))
                continue
            body = sub["approvalRequest"]
            base = base or body
            lines += body["lines"]
            if (i + 1) % 50 == 0:
                print(f"  초안 {i + 1}개", flush=True)
    finally:
        tok("claude-cs", ["CS_AGENT"])  # 시험 계정 역할을 되돌린다
    print("초안 제출", len(lines), "실패", len(failed))
    for pid, why in failed[:20]:
        print("  실패", pid, why)
    if not lines:
        return
    req = dict(base)
    req["lines"] = lines
    req["summary"] = f"상품 내용량 일괄 채우기 {len(lines)}건(옵션 표시 문구에서 읽음, 2026-11-19 BR-19) — 목록 deploy/lan/seed/net-content-2026-11-19.csv"
    st, cr = call("POST", "/change-requests", admin, req)
    print("변경 요청 HTTP", st, cr.get("changeRequestId"))
    if st != 201:
        raise SystemExit(json.dumps(cr, ensure_ascii=False)[:500])
    st, s2 = call("POST", f"/change-requests/{cr['changeRequestId']}/submissions", admin, version=cr.get("version"))
    print("변경 요청 제출 HTTP", st, "approvalId", s2.get("resultId"), "— 승인은 사람이 한다")


def main(do_apply):
    admin = tok("claude-dev")
    rows = candidates(admin)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    take = [r for r in rows if not r["skip"]]
    reasons = {}
    for r in rows:
        if r["skip"]:
            reasons[r["skip"]] = reasons.get(r["skip"], 0) + 1
    print("상품", len(rows), "채울 것", len(take), "건너뜀", reasons)
    print("목록:", os.path.relpath(OUT))
    if do_apply:
        apply(rows, admin)


if __name__ == "__main__":
    main("--apply" in sys.argv)
