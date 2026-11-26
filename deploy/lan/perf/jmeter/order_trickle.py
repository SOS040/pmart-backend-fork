"""주문·결제 소량 넣기(2026-11-27, 계획 docs/operations/2026-11-26-jmeter-load-test-plan.md §3).

  python -I -X utf8 deploy/lan/perf/jmeter/order_trickle.py --account CU-… --count 1
  python -I -X utf8 deploy/lan/perf/jmeter/order_trickle.py --account CU-… --count 5 --interval 60

- 고객 토큰: auth-service 직접 POST /internal/dev-customer-tokens(허용 목록 계정만, 15분 — 주문마다 새로 받는다).
- 순서: 장바구니 담기 → 주문서(POST /order-drafts) → 결제 시작(POST /order-drafts/{id}/checkouts)
  → 시험 결제 승인(POST /payment-confirmations, paymentKey TEST_AUTO_<pgOrderId> — 토스 부르지 않음).
- 실제 계정이면 주문이 그 앱 내역에 남고 알림이 간다. 넣은 주문은 out/orders.txt 에 한 줄씩(주문 ID·draft·금액) — 끝나면 취소용.
- 토큰은 출력·파일에 남기지 않는다.
"""

import argparse
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

GW = "https://192.168.0.40:8443/api/v1"
AUTH = "http://192.168.0.40:8501/api/v1"
CTX = ssl._create_unverified_context()
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = "perf-jmeter-order"


def call(method, url, token=None, body=None, device="perf-order-1"):
    h = {"X-Source-System": SRC, "X-Device-Id": device, "Accept": "application/json",
         "Idempotency-Key": str(uuid.uuid4())}
    if token:
        h["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        h["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    r = urllib.request.Request(url, method=method, headers=h, data=data)
    try:
        with urllib.request.urlopen(r, context=CTX, timeout=30) as x:
            raw = x.read()
            return x.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw[:500]}


def need(step, st, body, ok=(200, 201, 202)):
    if st not in ok:
        print(f"  ✗ {step} {st} {json.dumps(body, ensure_ascii=False)[:600]}")
        raise SystemExit(1)
    return body


def pick_items(token, mfc, target=16000):
    # 검색에서 지금 담을 수 있는 상품(판매중·AVAILABLE, [시험]·"N개 남음" 제외)을 최소 주문 금액(15,000원)을 넘게 — 상품마다 1개
    # (묶음 상품은 낱개 수로 10개 제한에 걸린다 — 4입 ×3 = 12)
    out, total, seen = [], 0, set()
    for q in ["두부", "우유", "라면", "계란", "생수", "김치", "햇반", "치즈", "만두", "삼겹살", "요거트", "커피"]:
        st, d = call("GET", f"{GW}/search/products?" + urllib.parse.urlencode({"q": q, "mfcId": mfc, "size": 20}), token)
        for i in d.get("items", []) if st == 200 else []:
            sku, price = i.get("representativeSku"), i.get("salePrice") or i.get("basePrice") or 0
            if (sku in seen or i.get("saleState") != "ON_SALE" or i.get("availability") != "AVAILABLE"
                    or "[시험]" in (i.get("name") or "") or price <= 0
                    or i.get("lowStockQty") is not None):  # "N개 남음" 상품은 빼기 — 시험 주문이 재고를 바닥내지 않게(2026-11-27)
                continue
            seen.add(sku)
            qty = 1
            out.append((sku, qty, i.get("name")))
            total += price * qty
            if total >= target or sum(1 for x in out if x[2]) % 2 == 0:
                break
        if total >= target:
            return out
    raise SystemExit(f"최소 주문 금액을 채울 상품을 못 찾음(합 {total}원)")


def delivery(token, mfc):
    # 바로 배송(SINGLE)이 되면 그것, 아니면(밤 NIGHT_TIME 등) 예약 배송(SCHEDULED) — 오늘·내일의 첫 AVAILABLE 시간대
    st, d = call("GET", f"{GW}/delivery-options?" + urllib.parse.urlencode({"mfcId": mfc, "itemAmount": 20000}), token)
    modes = {o["mode"]: o.get("availability") for o in d.get("options", [])} if st == 200 else {}
    if modes.get("SINGLE") == "AVAILABLE":
        return {"deliveryMode": "SINGLE"}
    for days in (0, 1):
        day = time.strftime("%Y-%m-%d", time.localtime(time.time() + days * 86400))
        st, s = call("GET", f"{GW}/slots?" + urllib.parse.urlencode({"mfcId": mfc, "date": day}), token)
        for sl in s.get("slots", []) if st == 200 else []:
            if sl.get("availability") == "AVAILABLE":
                return {"deliveryMode": "SCHEDULED", "arrivalWindow": sl["window"]}
    raise SystemExit(f"배송 방식 없음: {modes}")


def draft_until(tok, draft_id, ok, what, wait=60):
    # 결제 시작·승인은 비동기(ACCEPTED → QUERY_RESULT) — 앱처럼 주문서를 다시 읽어 기다린다
    t0, d = time.time(), {}
    while time.time() - t0 < wait:
        st, d = call("GET", f"{GW}/order-drafts/{draft_id}", tok)
        if st == 200 and ok(d):
            return d, time.time() - t0
        time.sleep(1)
    print(f"  ✗ {what} {wait}초 안에 안 됨: {json.dumps({k: d.get(k) for k in ('status', 'nextAction', 'orderId', 'payment')}, ensure_ascii=False)[:400]}")
    raise SystemExit(1)


def pay(tok, account, draft_id, order_hint, names, verbose):
    d, w1 = draft_until(tok, draft_id, lambda d: (d.get("payment") or {}).get("pgOrderId"), "결제창 값")
    p = d["payment"]
    pg, amount = p["pgOrderId"], p["amount"]
    st, pc = call("POST", f"{GW}/payment-confirmations", tok,
                  {"orderId": pg, "paymentKey": "TEST_AUTO_" + pg, "amount": amount})
    pc = need("결제 승인", st, pc)
    if verbose:
        print(f"  결제창 값 {w1:.1f}초 · 승인 응답", json.dumps(pc, ensure_ascii=False)[:300])
    d, w2 = draft_until(tok, draft_id, lambda d: d.get("nextAction") == "DONE" or d.get("status") in ("CONFIRMED", "PAID"), "주문 확정")
    order_id = d.get("orderId") or order_hint
    print(f"  ✓ {time.strftime('%H:%M:%S')} 주문 {order_id} · {amount}원 · 상태 {d.get('status')} · 결제창 {w1:.1f}초·확정 {w2:.1f}초 · {names}")
    os.makedirs(os.path.join(HERE, "out"), exist_ok=True)
    with open(os.path.join(HERE, "out", "orders.txt"), "a", encoding="utf-8", newline="\n") as f:
        f.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')}\t{account}\t{order_id}\t{draft_id}\t{pg}\t{amount}\n")
    return order_id


def one(account, verbose):
    st, t = call("POST", f"{AUTH}/internal/dev-customer-tokens", body={"accountId": account})
    tok = need("고객 토큰", st, t)["accessToken"]
    st, ctx = call("GET", f"{GW}/me/shopping-context", tok)
    ctx = need("쇼핑 맥락", st, ctx)
    mfc, addr = ctx.get("mfcId"), ctx.get("shoppingAddressId")
    items = pick_items(tok, mfc)
    st, cart = call("POST", f"{GW}/carts", tok, {"mfcId": mfc})
    cart = need("장바구니", st, cart)
    added = []  # 이 스크립트가 담은 줄만 — 실제 계정 장바구니의 다른 줄은 건드리지 않는다
    ver = cart["cartVersion"]
    try:
        for sku, qty, _ in items:
            st, it = call("POST", f"{GW}/carts/{cart['cartId']}/items", tok, {"sku": sku, "quantity": qty, "expectedVersion": ver})
            it = need("담기 " + sku, st, it)
            ver = it.get("cartVersion", ver)
            added.append((it["cartItemId"], sku, qty))
        body = {
            "purchaseIntentId": "perf-" + str(uuid.uuid4()),
            "items": [{"cartItemId": c, "sku": s, "quantity": q} for c, s, q in added],
            "shoppingAddressId": addr, "expectedMfcId": mfc, "outOfStockPreference": "CANCEL_OOS_ONLY"}
        body.update(delivery(tok, mfc))
        if verbose:
            print("  배송", {k: body[k] for k in ("deliveryMode", "arrivalWindow") if k in body})
        st, dr = call("POST", f"{GW}/order-drafts", tok, body)
        dr = need("주문서", st, dr)
        if verbose:
            print("  주문서", json.dumps({k: dr.get(k) for k in ("draftId", "quote")}, ensure_ascii=False)[:500])
        qv = (dr.get("quote") or {}).get("quoteVersion")
        st, co = call("POST", f"{GW}/order-drafts/{dr['draftId']}/checkouts", tok,
                      {"acceptedQuoteVersion": qv, "paymentMethod": "TOSS_PAYMENTS"})
        co = need("결제 시작", st, co)
        if verbose:
            print("  결제 시작", json.dumps(co, ensure_ascii=False)[:300])
        names = ", ".join(f"{n} ×{q}" for _, q, n in items)
        return pay(tok, account, dr["draftId"], co.get("resultId"), names, verbose)
    finally:
        # 주문으로 빠지지 않은 줄(실패 등)은 뺀다 — 이미 빠졌으면 404 는 무시
        for c, _, _ in added:
            call("DELETE", f"{GW}/carts/{cart['cartId']}/items/{c}", tok)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--account", required=True)
    ap.add_argument("--count", type=int, default=1)
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()
    for n in range(a.count):
        if n:
            time.sleep(a.interval)
        print(f"주문 {n + 1}/{a.count}")
        one(a.account, a.verbose)


if __name__ == "__main__":
    sys.exit(main())
