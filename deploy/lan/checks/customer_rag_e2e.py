"""LAN 고객 RAG 확인(customer-assistant-service 0.1.0, ADR-0051).

실행: python -I deploy/lan/checks/customer_rag_e2e.py
- 늘 하는 것: 게이트웨이 경로, 비회원(실제 guest-sessions 토큰)·운영자(claude-dev) 403, 헬스.
- 고객 토큰이 있을 때만: 질문 답변(근거·문의 연결·주문 고르기). 고객 토큰은 LAN 서명키로 만들지 않는다 — 고객앱에서 Google 로그인한
  토큰을 사용자가 따로 터미널에서 deploy/lan/.secrets/customer-token 파일에 넣는다(15분 유효, 저장소 제외). 없으면 건너뛴다.
필요: deploy/lan/.secrets/claude-dev-login·claude-dev-password.
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

sys.stdout.reconfigure(encoding="utf-8")
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
SECRETS = os.path.join(os.path.dirname(__file__), "..", ".secrets")
ok_count, total = 0, 0


def read_secret(name):
    p = os.path.join(SECRETS, name)
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return f.read().strip() or None


def http(method, url, token=None, body=None, source="customer-app", extra=None, key=None):
    h = {"Accept": "application/json", "X-Source-System": source}
    if extra:
        h.update(extra)
    if token:
        h["Authorization"] = "Bearer " + token
    data = None
    if method != "GET":
        h["Idempotency-Key"] = key or str(uuid.uuid4())
    if body is not None:
        h["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=70) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


def step(name, got, want):
    global ok_count, total
    total += 1
    good = got == want if not isinstance(want, (set, tuple)) else got in want
    ok_count += good
    print(f"{'OK  ' if good else 'FAIL'} {name}: {got} (기대 {want})")


st, _ = http("GET", GW.replace("/api/v1", "") + "/api/v1/customer-assistant/recent-orders")
step("게이트웨이 경로(토큰 없음 → 401)", st, 401)

st, b = http("POST", f"{GW}/auth/guest-sessions", body={}, extra={"X-Device-Id": "claude-lan-customer-rag-check"})
guest = b.get("tokens", {}).get("accessToken")
st, _ = http("POST", f"{GW}/customer-assistant/questions", guest, {"question": "봉투값은 얼마인가요?"})
step("비회원 질문 → 403(C2)", st, 403)

st, b = http("POST", f"{GW}/auth/operator-logins", source="backoffice-web",
             body={"loginId": read_secret("claude-dev-login"), "password": read_secret("claude-dev-password")})
admin = b.get("tokens", {}).get("accessToken")
st, _ = http("POST", f"{GW}/customer-assistant/questions", admin, {"question": "봉투값은 얼마인가요?"}, source="backoffice-web")
step("운영자 질문 → 403(고객만)", st, 403)

customer = read_secret("customer-token")
if not customer:
    print("     고객 토큰 없음(deploy/lan/.secrets/customer-token) — 답변 확인은 건너뜀")
else:
    cases = [
        ("상품 준비가 시작된 주문도 취소할 수 있나요?", {"ANSWERED"}),
        ("봉투값도 환불되나요?", {"ANSWERED"}),
        ("배달팁은 얼마예요?", {"ANSWERED", "NO_GROUNDING"}),
        ("회원 등급별 생일 쿠폰은 언제 지급되나요?", {"NO_GROUNDING"}),
        ("주문한 거 언제 와요?", {"REALTIME_DATA_REQUIRED"}),
        ("지금 바로 환불해 주세요", {"OUT_OF_SCOPE"}),
    ]
    for q, want in cases:
        st, b = http("POST", f"{GW}/customer-assistant/questions", customer, {"question": q})
        step(f"질문 \"{q}\"", b.get("state", st), want)
        print("     답:", (b.get("answer") or "")[:160].replace("\n", " "),
              "| 근거:", [c.get("title") + " > " + str(c.get("section")) for c in b.get("citations", [])][:2],
              "| 연결:", (b.get("handoff") or {}).get("target"), "| 최근 주문:", len(b.get("recentOrders") or []))
print(f"---- 요약: {ok_count} / {total} 통과")
