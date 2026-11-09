"""LAN MFC 처음부터 끝까지 확인(실제 API — 주문 조정 서비스가 부르는 내부 API + 센터 관리자·작업자 LAN 계정). 토큰·비밀번호는 출력하지 않는다.

예약 → 확정 → 작업 접수 → PICK 배정·시작·피킹 → PACK 배정·시작 → 점원 배차 요청 → 밀봉(READY) → 전체 중지(취소) → 복귀 작업(추천 칸)
→ 재고 원복(처음과 같은 H·A) → 센터 관리자 이동 지시(긴급, 고회전 칸) → 작업자 다음 작업 받기(MOVE)·집기·내려놓기 → 되돌리는 이동. 시험 주문은 만들었다가 취소·복귀하므로 재고는 원래대로 돌아간다. 고객 로그인·결제(Toss)·라이더 인계는 없다.

실행: python -I deploy/lan/checks/mfc_e2e.py   (PYTHONIOENCODING=utf-8 권장)
필요: deploy/lan/.secrets/claude-dev-login·claude-dev-password(LAN 전용 OPERATOR_ADMIN). 계정 claude-mfc-sup·claude-mfc-wrk 는 실행마다 만들거나 비밀번호를 새로 정한다.
2026-11-08 — 이 시험으로 mfc-capacity 가 RETURN 작업 배정을 거절하던 결함을 찾았다(dab9873).
"""
import base64
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
INV = os.environ.get("PMART_INVENTORY", "http://192.168.0.222:8301/api/v1")
FUL = os.environ.get("PMART_FULFILLMENT", "http://192.168.0.222:8302/api/v1")
MFC = os.environ.get("PMART_MFC", "MFC-SEED-01")
ORCH_SRC = "order-orchestration-service"
results = []


def read_secret(name):
    with open(os.path.join(ROOT, "deploy", "lan", ".secrets", name), encoding="utf-8") as f:
        return f.read().strip()


def http(method, url, token=None, body=None, version=None, source="claude-mfc-e2e"):
    headers = {"Accept": "application/json, application/problem+json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if method != "GET":
        headers["Idempotency-Key"] = str(uuid.uuid4())
        headers["X-Source-System"] = source
    if version is not None:
        headers["X-Expected-Version"] = str(version)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    for attempt in range(10):
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                b = json.loads(raw)
            except Exception:
                b = {"raw": raw[:200].decode("utf-8", "replace")}
            if e.code in (429, 503) and attempt < 9:
                time.sleep(3)
                continue
            return e.code, b


def step(name, res, *ok):
    st, b = res
    good = st in ok
    results.append((name, st, good))
    print(("OK  " if good else "FAIL"), name, st, "" if good else (b.get("code"), b.get("detail", b.get("raw"))))
    if not good:
        summary()
        raise SystemExit(1)
    return b


def summary():
    print("---- 요약:", sum(1 for r in results if r[2]), "/", len(results), "통과")


def sub(token):
    p = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))["sub"]


def login(login_id, password):
    st, b = http("POST", f"{GW}/auth/operator-logins", body={"loginId": login_id, "password": password})
    if st != 200:
        raise SystemExit(f"로그인 {login_id} 실패: HTTP {st} {b.get('code', '')}")
    return b["tokens"]["accessToken"]


def ensure_operator(admin, login_id, name, roles, centers):
    password = "Pm!" + secrets.token_urlsafe(18)
    found, cursor = None, None
    while True:
        q = f"{GW}/operator-accounts?size=200" + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
        st, b = http("GET", q, admin)
        for it in b.get("items", []):
            if it.get("loginId") == login_id:
                found = it
        cursor = b.get("nextCursor")
        if found or not cursor:
            break
    if not found:
        http("POST", f"{GW}/operator-accounts", admin,
             {"loginId": login_id, "displayName": name, "initialPassword": password, "roles": roles, "centers": centers})
    else:
        oid = found["operatorId"]
        http("PUT", f"{GW}/operator-accounts/{oid}", admin,
             {"displayName": name, "roles": roles, "centers": centers, "state": "ACTIVE"}, version=found["version"])
        http("POST", f"{GW}/operator-accounts/{oid}/password-resets", admin, {"temporaryPassword": password})
    return login(login_id, password)


admin = login(read_secret("claude-dev-login"), read_secret("claude-dev-password"))
sup = ensure_operator(admin, "claude-mfc-sup", "Claude MFC 감독(LAN)", ["MFC_SUPERVISOR"], [MFC])
wrk = ensure_operator(admin, "claude-mfc-wrk", "Claude MFC 작업자(LAN)", ["MFC_WORKER"], [MFC])
wid = sub(wrk)
# 0. 작업자 등록(이미 있으면 409)
st, b = http("POST", f"{GW}/mfcs/{MFC}/workers", sup,
             {"workerId": wid, "displayName": "Claude MFC 작업자(LAN)",
              "duty": {"taskTypes": ["PICK", "PACK", "RETURN"], "temperatureZones": ["AMBIENT", "CHILLED", "FROZEN"]}})
print("작업자 등록", st, b.get("code", ""))
if st not in (201, 409):
    raise SystemExit(1)

# 1. 포장 위치·재고 고르기
def all_locations():
    """센터의 ACTIVE 위치 전부(2026-11-09 도면 칸 6,762개 등록 뒤 — 한 쪽 200개로는 포장·카트 위치가 안 나온다)."""
    out, cursor = [], None
    while True:
        u = f"{GW}/locations?mfcId={MFC}&state=ACTIVE&size=200" + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
        st, b = http("GET", u, sup)
        if st != 200:
            return st, b
        out += b.get("items", [])
        cursor = (b.get("page") or {}).get("nextCursor")
        if not cursor:
            return 200, {"items": out}


locs = step("위치 조회", all_locations(), 200)["items"]
pak = next(l["locationCode"] for l in locs if l["zone"] == "PACKING")
temp_of = {l["locationCode"]: l["temperatureZone"] for l in locs}
snap = step("재고 스냅샷", http("GET", f"{INV}/internal/snapshots?mfcId={MFC}&size=200"), 200)
cands = {}
for u in snap.get("items", []):
    if u.get("qualityState") == "NORMAL" and u.get("workState") == "STORED" and (u.get("pickableQty") or 0) >= 3 \
            and temp_of.get(u["locationCode"]) == "AMBIENT":
        cands.setdefault(u["sku"], u)
skus = list(cands)[:8]
print("포장 위치", pak, "후보 SKU", len(skus))

av_before = {s: http("GET", f"{INV}/internal/availability?mfcId={MFC}&sku={s}")[1]["items"][0] for s in skus}
order = "E2E-" + uuid.uuid4().hex[:8]
rid = None
for i in range(len(skus) - 1):
    a, c = skus[i], skus[i + 1]
    st, b = http("POST", f"{INV}/internal/reservations", None,
                 {"orderId": order, "mfcId": MFC,
                  "lines": [{"orderLineId": "L1", "sku": a, "qty": 2}, {"orderLineId": "L2", "sku": c, "qty": 1}]},
                 source=ORCH_SRC)
    if st == 201:
        rid, sku1, sku2 = b["resultId"], a, c
        break
    print("  예약 거절", a, c, st, b.get("code"))
if not rid:
    raise SystemExit("예약할 수 있는 상품 쌍이 없음")
av0 = {s: av_before[s] for s in (sku1, sku2)}  # 예약 전
results.append(("예약(HELD)", 201, True))
print("OK   예약", rid, sku1, "x2", sku2, "x1")
step("예약 확정(결제 완료 가정)", http("POST", f"{INV}/internal/reservations/{rid}/confirmations", None,
                                 {"orderId": order, "paymentAttemptId": "PAY-" + order}, source=ORCH_SRC), 200)

# 2. 작업 접수
ship = order + "-S1"
wo = step("작업 접수(ConfirmOrder)", http("POST", f"{FUL}/internal/work-orders", None, {
    "orderId": order, "workVersion": 1, "mfcId": MFC, "reservationId": rid, "deliveryMode": "SINGLE",
    "outOfStockPreference": "CANCEL_OOS_ONLY",
    "shipments": [{"shipmentId": ship, "lines": [
        {"orderLineId": "L1", "sku": sku1, "qty": 2, "productName": "E2E 상품1"},
        {"orderLineId": "L2", "sku": sku2, "qty": 1, "productName": "E2E 상품2"}]}]}, source=ORCH_SRC), 201)
print("     결정", wo.get("decision") or wo.get("result") or wo.get("state"))
pick, pack, ret = ship + "-PICK", ship + "-PACK", ship + "-RETURN"


def assign_and_start(task, ttype):
    # 다음 작업 받기(시스템 배정, capacity 0.6.0) — 센터에 더 급한 다른 작업이 있으면 센터 관리자 배정으로
    pulled = None
    for _ in range(20):
        st, b = http("POST", f"{GW}/mfcs/{MFC}/workers/{wid}/next-tasks", wrk)
        if st in (200, 201) and b.get("taskId") == task:
            pulled = b
            break
        if st == 409 and b.get("code") == "NO_TASK_AVAILABLE":
            time.sleep(1)  # 작업이 READY 로 보이기 전
            continue
        break
    if pulled:
        results.append((f"{ttype} 다음 작업 받기(시스템 배정)", 201, True))
        print("OK  ", f"{ttype} 다음 작업 받기(시스템 배정)", pulled.get("source"))
        det = pulled.get("detail") or {}
        print("     상세", [(l.get("sku"), l.get("qty"), l.get("fromLocation"), l.get("toLocation")) for l in det.get("lines", [])])
        step(f"{ttype} 받은 작업 상세(capacity 0.9.1)", (200 if det.get("available") and det.get("lines") else 409, det), 200)
    else:
        print("     (다음 작업 받기가 다른 작업을 줌 — 센터 관리자 배정으로)", st, b.get("taskId") or b.get("code"))
        step(f"{ttype} 배정(센터 관리자)", http("POST", f"{GW}/assignments", sup,
                                        {"mfcId": MFC, "taskId": task, "taskType": ttype, "workerId": wid}, version=0), 201, 200)
    for _ in range(30):
        st, t = http("GET", f"{GW}/tasks/{task}", wrk)
        if st == 200 and t.get("state") == "ASSIGNED":
            break
        time.sleep(1)
    step(f"{ttype} 담당 반영(ASSIGNED)", (200 if t.get("state") == "ASSIGNED" else 409, t), 200)
    step(f"{ttype} 시작", http("POST", f"{GW}/tasks/{task}/starts", wrk, {"workerId": wid, "assignmentVersion": 1}), 201)


# 3. 피킹
assign_and_start(pick, "PICK")
d = step("피킹 지시 조회", http("GET", f"{GW}/tasks/{pick}", wrk), 200)
step("피킹 화면 상태(pickStatus, fulfillment 0.4.9)", (200 if all(p.get("pickStatus") for l in d["lines"] for p in l.get("picks", [])) else 409, {}), 200)
step("확인 필요 포장 목록(0.4.9)", http("GET", f"{GW}/packages?mfcId={MFC}&checkRequired=true", wrk), 200)
# 피킹 방문 순서(fulfillment 0.4.10 · capacity 0.10.0, ADR-0050 B2): 시작 때 capacity 가 정한 순서 — 조각마다 visitSeq, 줄은 그 순서
seqs = [p.get("visitSeq") for l in d["lines"] for p in l.get("picks", [])]
print("     방문 순서", d.get("routeMethod"), [(p.get("visitSeq"), (p.get("locations") or [{}])[0].get("locationCode")) for l in d["lines"] for p in l.get("picks", [])])
per_line = [[p.get("visitSeq") for p in l.get("picks", [])] for l in d["lines"] if l.get("picks")]
ordered_ok = all(x == sorted(x) for x in per_line) and [x[0] for x in per_line] == sorted(x[0] for x in per_line)
step("피킹 방문 순서(ROUTING/NEAREST_FALLBACK)", (200 if d.get("routeMethod") in ("ROUTING", "NEAREST_FALLBACK") and all(seqs) and ordered_ok else 409, {}), 200)
for line in d["lines"]:
    need = line["remainingQty"]
    for p in line.get("picks", []):
        for loc in sorted(p.get("locations", []), key=lambda x: -(x.get("allocatedQty") or 0)):
            if need <= 0:
                break
            q = min(need, p["remainingQty"], loc["qty"])
            if q <= 0:
                continue
            step(f"피킹 {line['orderLineId']} {q}개 @{loc['locationCode']}", http("POST", f"{GW}/tasks/{pick}/executions", wrk, {
                "executionType": "PICK", "workerId": wid, "fromLocation": loc["locationCode"], "toLocation": pak,
                "lines": [{"orderLineId": line["orderLineId"], "sku": line["sku"], "lotId": p["lotId"], "pickedQty": q}]}), 202, 201)
            need -= q
step("피킹 완료", (200 if http("GET", f"{GW}/tasks/{pick}", wrk)[1].get("state") == "COMPLETED" else 409, {}), 200)

# 4. 포장 + 배차 요청(점원, 포장 중)
for _ in range(20):
    if http("GET", f"{GW}/tasks/{pack}", wrk)[1].get("state") in ("READY", "ASSIGNED"):
        break
    time.sleep(1)
assign_and_start(pack, "PACK")
dr = step("배차 요청(점원, 예상 포장 5분)", http("POST", f"{GW}/shipments/{ship}/dispatch-requests", wrk, {"estimatedPackMinutes": 5}), 201)
print("     trigger", dr.get("trigger"), "예상", dr.get("estimatedReadyAt"))
step("배차 요청 다시 → 같은 요청", http("POST", f"{GW}/shipments/{ship}/dispatch-requests", wrk, {"estimatedPackMinutes": 9}), 200)
pk = step("포장 밀봉", http("POST", f"{GW}/shipments/{ship}/packages", wrk, {
    "bagCount": 1, "contents": [{"orderLineId": "L1", "sku": sku1, "qty": 2}, {"orderLineId": "L2", "sku": sku2, "qty": 1}]}), 201)
print("     포장", pk.get("packageId"), pk.get("state"))
sv = step("출고 조회", http("GET", f"{GW}/shipments/{ship}", wrk), 200)
step("출고 READY + 배차 요청 MANUAL",
     (200 if sv.get("state") == "READY" and (sv.get("dispatchRequest") or {}).get("trigger") == "MANUAL" else 409, sv), 200)
res = step("인계 판정", http("GET", f"{GW}/packages/{pk['packageId']}/handover-resolution", wrk), 200)
print("     verdict", res.get("verdict"), "checkRequired", res.get("checkRequired"))
av1 = {s: http("GET", f"{INV}/internal/availability?mfcId={MFC}&sku={s}")[1]["items"][0] for s in (sku1, sku2)}
print("     재고 예약 R:", {s: (av0[s]["reserved"], "→", av1[s]["reserved"]) for s in av0})

# 5. 전체 중지(취소) → 취소 복귀 → 복귀 작업(추천 칸)
stop = step("전체 중지(취소)", http("POST", f"{FUL}/internal/stop-requests", None, {
    "stopRequestId": "STOP-" + order, "orderId": order, "scope": "FULL",
    "lines": [{"orderLineId": "L1", "qty": 2}, {"orderLineId": "L2", "qty": 1}]}, source=ORCH_SRC), 201, 200)
for _ in range(40):
    st, s2 = http("GET", f"{FUL}/internal/stop-requests/STOP-{order}")
    if s2.get("state") == "RESOLVED":
        break
    time.sleep(2)
step("중지 RESOLVED(포장 무효·취소 복귀)", (200 if s2.get("state") == "RESOLVED" else 409, s2), 200)
assign_and_start(ret, "RETURN")
rd = step("복귀 지시 조회", http("GET", f"{GW}/tasks/{ret}", wrk), 200)
for line in rd["lines"]:
    for p in line.get("picks", []):
        sug = p.get("suggestedToLocations") or []
        print("     복귀 추천", line["sku"], sug)
        to = sug[0] if sug else None
        step(f"추천 칸 있음 {line['orderLineId']}", (200 if to else 409, {}), 200)
        frm = p["locations"][0]["locationCode"]
        step(f"복귀 {line['orderLineId']} {p['remainingQty']}개 → {to}", http("POST", f"{GW}/tasks/{ret}/executions", wrk, {
            "executionType": "RETURN", "workerId": wid, "fromLocation": frm, "toLocation": to,
            "lines": [{"orderLineId": line["orderLineId"], "sku": line["sku"], "lotId": p["lotId"],
                       "pickedQty": p["remainingQty"], "resultQuality": "NORMAL"}]}), 202, 201)
step("복귀 완료", (200 if http("GET", f"{GW}/tasks/{ret}", wrk)[1].get("state") == "COMPLETED" else 409, {}), 200)
av2 = {s: http("GET", f"{INV}/internal/availability?mfcId={MFC}&sku={s}")[1]["items"][0] for s in (sku1, sku2)}
same = all(av2[s]["onHand"] == av0[s]["onHand"] and av2[s]["available"] == av0[s]["available"] for s in av0)
print("     재고 H/A 처음:", {s: (av0[s]["onHand"], av0[s]["available"]) for s in av0}, "끝:", {s: (av2[s]["onHand"], av2[s]["available"]) for s in av2})
step("재고 원복(H·A 처음과 같음)", (200 if same else 409, {}), 200)

# 6. 센터 관리자 이동 지시(긴급) → 작업자 다음 작업 받기(MOVE) → 집기·내려놓기 → 되돌리는 이동 — 작업자 쪽 4단계(capacity 0.9.0·inbound 0.7.1)
w = step("작업자 조회", http("GET", f"{GW}/mfcs/{MFC}/workers/{wid}", sup), 200)
if "MOVE" not in (w.get("effectiveTaskTypes") or []):
    q = w.get("qualification") or {}
    types = sorted(set((q.get("taskTypes") or []) + ["PICK", "PACK", "RETURN", "MOVE"]))
    step("자격에 MOVE", http("POST", f"{GW}/mfcs/{MFC}/workers/{wid}/qualification-changes", sup,
                            {"taskTypes": types, "reason": "E2E 이동"}, version=w.get("qualificationVersion", q.get("qualificationVersion"))), 200)
    w = http("GET", f"{GW}/mfcs/{MFC}/workers/{wid}", sup)[1]
    duty = sorted(set(((w.get("duty") or {}).get("taskTypes") or []) + ["MOVE"]))
    step("업무에 MOVE", http("POST", f"{GW}/mfcs/{MFC}/workers/{wid}/duty-changes", sup,
                            {"taskTypes": duty, "reason": "E2E 이동"}, version=(w.get("duty") or {}).get("dutyVersion")), 200)
cart = next((l["locationCode"] for l in locs if l["zone"] == "CART"), None)
step("이동 카트 있음", (200 if cart else 409, {}), 200)
loc_of = {l["locationCode"]: l for l in locs}
snap = http("GET", f"{INV}/internal/snapshots?mfcId={MFC}&size=200")[1]


def try_move(units, dests, urgent):
    for u in units:
        for d in dests:
            if d == u["locationCode"]:
                continue
            st, b = http("POST", f"{GW}/move-orders", sup, {
                "mfcId": MFC, "sku": u["sku"], "lotId": u["lotId"], "fromLocation": u["locationCode"],
                "toLocation": d, "qty": 1, "reason": "E2E 이동", "urgent": urgent})
            if st == 201:
                return b
            print("     이동 지시 거절", u["sku"], u["locationCode"], "→", d, st, b.get("code"))
    return None


def carry(mv, label):
    mid = mv["moveId"]
    got = None
    for _ in range(20):
        st, b = http("POST", f"{GW}/mfcs/{MFC}/workers/{wid}/next-tasks", wrk)
        if st in (200, 201) and b.get("taskId") == mid:
            got = b
            break
        if st == 409 and b.get("code") == "NO_TASK_AVAILABLE":
            time.sleep(1)
            continue
        print("     다음 작업 받기가 다른 것을 줌", st, b.get("taskId") or b.get("code"))
        break
    step(f"{label} 다음 작업 받기 → {got and got.get('taskType')}", (200 if got and got.get("taskType") == "MOVE" else 409, got or {}), 200)
    ln = ((got.get("detail") or {}).get("lines") or [{}])[0]
    step(f"{label} 받은 이동 상세(출발·도착 칸)", (200 if ln.get("fromLocation") == mv["fromLocation"] and ln.get("toLocation") == mv["toLocation"] else 409, ln), 200)
    m = {}
    for _ in range(30):
        m = http("GET", f"{GW}/move-orders/{mid}", wrk)[1]
        if m.get("assigneeId") == wid:
            break
        time.sleep(1)
    step(f"{label} 담당 반영(assigneeId)", (200 if m.get("assigneeId") == wid else 409, m), 200)
    step(f"{label} 센터 관리자는 담당이 아니라 못 집음", http("POST", f"{GW}/move-orders/{mid}/pickups", sup, {"cartLocation": cart, "qty": 1}), 409)
    step(f"{label} 집기 → {cart}", http("POST", f"{GW}/move-orders/{mid}/pickups", wrk, {"cartLocation": cart, "qty": 1}), 200)
    d = step(f"{label} 내려놓기 → {mv['toLocation']}", http("POST", f"{GW}/move-orders/{mid}/drops", wrk, {"toLocation": mv["toLocation"], "qty": 1}), 200)
    step(f"{label} 완료", (200 if d.get("state") == "COMPLETED" else 409, d), 200)
    return d


units = [u for u in snap.get("items", [])
         if u.get("qualityState") == "NORMAL" and u.get("workState") == "STORED" and (u.get("pickableQty") or 0) >= 2
         and loc_of.get(u["locationCode"], {}).get("temperatureZone") == "AMBIENT"][:6]
hot = [l["locationCode"] for l in locs if l.get("highTurnover") and l["temperatureZone"] == "AMBIENT"
       and l["zone"] in ("STORAGE", "PICKING")]
print("     고회전 상온 칸", hot[:5], "후보", len(units))
mv = try_move(units, hot[:5], True)
if mv:
    step("이동 지시(긴급)", (201, mv), 201)
    carry(mv, "이동")
    back = {"sku": mv["sku"], "lotId": mv["lotId"], "locationCode": mv["toLocation"]}
    mv2 = try_move([back], [mv["fromLocation"]], False)
    step("되돌리는 이동 지시", (201 if mv2 else 409, mv2 or {}), 201)
    carry(mv2, "되돌림")
else:
    step("이동 지시(고회전 칸)", (409, {"code": "NO_CANDIDATE"}), 201)
# 7. 배정 추천(OR-Tools)에 열린 이동이 들어가는가(capacity 0.9.2) — 추천만, 확정 안 함. 이동 지시는 취소해 둔다
mv3 = try_move(units, hot[:5], False)
step("추천용 이동 지시", (201 if mv3 else 409, mv3 or {}), 201)
pl = step("배정 추천 계산", http("POST", f"{GW}/assignment-plans", sup, {"mfcId": MFC, "horizonMinutes": 60}), 201)
ids = [i.get("taskId") for i in pl.get("items", [])] + [u.get("taskId") for u in pl.get("unassigned", [])]
print("     추천", pl.get("state"), "항목", len(pl.get("items", [])), "미배정", [(u.get("taskId"), u.get("reason")) for u in pl.get("unassigned", [])][:5], pl.get("limitations"))
step("추천에 이동 지시 포함", (200 if mv3["moveId"] in ids else 409, {}), 200)
step("추천용 이동 취소", http("POST", f"{GW}/move-orders/{mv3['moveId']}/cancellations", sup, {"reason": "E2E 추천 확인 뒤 취소"}), 200)
summary()
