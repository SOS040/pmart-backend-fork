"""LAN 운영자 RAG 확인(ADR-0051): 게이트웨이로 정책 질문을 보내 검색·생성 모드, 근거, 응답 시간을 본다.

실행: PYTHONIOENCODING=utf-8 python -I deploy/lan/checks/rag_e2e.py
필요: deploy/lan/.secrets/keycloak-claude-dev-password·keycloak-admin-password(직원 토큰은 staff_auth.py — employee-auth-service 시험용 교환). 토큰·비밀번호는 출력하지 않는다.
질문은 검색 평가 질문(eval/questions.yml) 일부 + 근거가 없어야 하는 질문 + 실시간 조회 질문.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from staff_auth import staff_token  # noqa: E402 — 직원 토큰(employee-auth-service, 2026-11-13)
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
SECRETS = os.path.join(os.path.dirname(__file__), "..", ".secrets")

QUESTIONS = [
    ("결품", "피킹 중에 결품이 나면 주문은 어떻게 처리돼?", "GROUNDED"),
    ("봉투", "봉투값은 언제 환불돼?", "ANY"),
    ("배정", "MFC 작업 배정 추천은 어떤 순서로 목표를 정해?", "GROUNDED"),
    ("고객센터취소", "상품준비가 시작된 주문을 고객센터에서 취소할 수 있어?", "GROUNDED"),  # 2026-11-15 기술 질문(서비스 간 호출) 대신 — 운영자 RAG 는 업무 ADR 만
    ("근거없음", "직원 휴가 신청은 어떻게 해?", "NONE"),
    ("실시간", "주문 ORD-123 지금 상태 알려줘", "REALTIME"),
]


def read_secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as f:
        return f.read().strip()


def http(method, url, token=None, body=None):
    headers = {"Accept": "application/json, application/problem+json", "X-Source-System": "backoffice-web"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        headers["Idempotency-Key"] = str(uuid.uuid4())
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw[:200].decode(errors="replace")}


token, _ = staff_token("claude-dev")

ok = 0
for tag, q, expect in QUESTIONS:
    t0 = time.time()
    st, r = http("POST", f"{GW}/assistant/policy-questions", token, {"question": q})
    secs = time.time() - t0
    mode, rmode = r.get("answerMode"), r.get("retrievalMode")
    cites = r.get("citations") or r.get("evidence") or []
    status = r.get("status") or r.get("outcome") or r.get("code")
    text = (r.get("answer") or r.get("detail") or "").replace("\n", " ")
    good = bool(st == 200 and (
        (expect == "GROUNDED" and mode in ("GENERATIVE", "BASELINE_EXTRACTIVE") and cites)
        or (expect == "NONE" and mode in ("NONE", None))
        or (expect == "REALTIME" and ("REALTIME" in json.dumps(r, ensure_ascii=False)))
        or expect == "ANY"))
    ok += good
    print(f"{'OK  ' if good else 'FAIL'} [{tag}] HTTP {st} {secs:.1f}s answerMode={mode} retrieval={rmode} "
          f"status={status} 근거 {len(cites)}")
    print(f"     {text[:220]}")
    for c in cites[:3]:
        print(f"     - {c.get('path') or c.get('documentPath')} § {c.get('section') or ''} [{c.get('authority')}]")
model = r.get("model")
print(f"---- 요약: {ok} / {len(QUESTIONS)} 기대대로 · 모델 {json.dumps(model, ensure_ascii=False)}")
