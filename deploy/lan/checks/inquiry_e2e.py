"""LAN 고객센터 문의 확인(case-service 0.2.0): 비회원 차단, 상담원(CS_AGENT) 목록, 상담원 아닌 운영자 차단.

실행: python -I deploy/lan/checks/inquiry_e2e.py
필요: deploy/lan/.secrets/claude-dev-login·claude-dev-password(LAN 전용 OPERATOR_ADMIN). 상담원 계정 claude-cs 는 실행마다 비밀번호를 새로 정한다.
고객 작성·본인 조회는 고객 토큰이 필요해 여기서 하지 않는다(LAN 서명키로 고객 토큰을 만들지 않음) — 통합 시험 InquiriesIT 가 본다.
"""
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

sys.stdout.reconfigure(encoding="utf-8")
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
SECRETS = os.path.join(os.path.dirname(__file__), "..", ".secrets")
ok_count, total = 0, 0


def read_secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as f:
        return f.read().strip()


def http(method, url, token=None, body=None, version=None, source="backoffice-web", extra=None):
    h = {"Accept": "application/json", "X-Source-System": source}
    if extra:
        h.update(extra)
    if token:
        h["Authorization"] = "Bearer " + token
    if version is not None:
        h["X-Expected-Version"] = str(version)
    data = None
    if method != "GET":
        h["Idempotency-Key"] = str(uuid.uuid4())
    if body is not None:
        h["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


def step(name, st, want):
    global ok_count, total
    total += 1
    good = st == want
    ok_count += good
    print(f"{'OK  ' if good else 'FAIL'} {name} {st} (기대 {want})")


def login(login_id, password):
    st, b = http("POST", f"{GW}/auth/operator-logins", body={"loginId": login_id, "password": password})
    if st != 200:
        sys.exit(f"로그인 {login_id} 실패: HTTP {st} {b.get('code', '')}")
    return b["tokens"]["accessToken"]


def ensure_operator(admin, login_id, name, roles):
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
        st, b = http("POST", f"{GW}/operator-accounts", admin,
                     {"loginId": login_id, "displayName": name, "initialPassword": password, "roles": roles, "centers": []})
        print(f"     계정 만들기 {login_id}: HTTP {st} {b.get('code', '')}")
    else:
        oid = found["operatorId"]
        st, _ = http("PUT", f"{GW}/operator-accounts/{oid}", admin,
                     {"displayName": name, "roles": roles, "centers": [], "state": "ACTIVE"}, version=found["version"])
        print(f"     계정 갱신 {login_id}: HTTP {st}")
        st, b = http("POST", f"{GW}/operator-accounts/{oid}/password-resets", admin, {"temporaryPassword": password},
                     extra={"Accept": "*/*"})  # 204 — Accept: application/json 이면 406
        print(f"     비밀번호 초기화 {login_id}: HTTP {st} {b.get('code', '')}")
    return login(login_id, password)


admin = login(read_secret("claude-dev-login"), read_secret("claude-dev-password"))
cs = ensure_operator(admin, "claude-cs", "Claude 고객센터 상담원(LAN)", ["CS_AGENT"])

st, b = http("POST", f"{GW}/auth/guest-sessions", body={}, source="customer-app",
             extra={"X-Device-Id": "claude-lan-inquiry-check"})
guest = b.get("tokens", {}).get("accessToken")
st, _ = http("POST", f"{GW}/inquiries", guest, {"category": "OTHER", "body": "비회원 문의"}, source="customer-app")
step("비회원 문의 남기기 → 차단(로그인 필수, C2)", st, 403)
st, b = http("GET", f"{GW}/inquiries?status=RECEIVED", cs)
step("상담원 문의 목록", st, 200)
print("     접수 상태 문의", len(b.get("items", [])), "건")
st, _ = http("GET", f"{GW}/inquiries", admin)
step("상담원 아닌 운영자 목록 → 403", st, 403)
st, _ = http("POST", f"{GW}/inquiries", cs, {"category": "OTHER", "body": "x"})
step("운영자가 문의 남기기 → 403(회원만)", st, 403)
st, _ = http("GET", f"{GW}/inquiries/INQ-none", cs)
step("없는 문의 → 404", st, 404)
print(f"---- 요약: {ok_count} / {total} 통과")
