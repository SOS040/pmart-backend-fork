"""LAN 고객 RAG 답변 평가(eval/customer-answers.json). 기계 채점 — 사람이 답을 다시 읽어 판정한다.

실행: python -I deploy/lan/checks/customer_rag_eval.py [보고서.json]
필요: 고객 토큰 deploy/lan/.secrets/customer-token(15분 유효, 저장소 제외). LAN 서명키로 고객 토큰을 만들려면 사용자 허락이 있어야 한다.
요청 제한(1분 5개)에 걸리지 않게 질문 사이 13초 쉰다(37문항 약 8분). 같은 고객으로 하루 50개를 넘기지 않게 회차마다 다른 시험 고객을 쓴다.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.stdout.reconfigure(encoding="utf-8")
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
HERE = os.path.dirname(__file__)
token = open(os.path.join(HERE, "..", ".secrets", "customer-token"), encoding="utf-8").read().strip()
qs = json.load(open(os.path.join(HERE, "eval", "customer-answers.json"), encoding="utf-8"))["questions"]
out_path = sys.argv[1] if len(sys.argv) > 1 else None


def ask(q):
    h = {"Content-Type": "application/json", "Accept": "application/json", "X-Source-System": "customer-app",
         "Idempotency-Key": str(uuid.uuid4()), "Authorization": "Bearer " + token}
    req = urllib.request.Request(f"{GW}/customer-assistant/questions", json.dumps({"question": q}).encode(), h, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=70) as r:
            return r.status, json.loads(r.read()), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}"), time.time() - t0


rows, good = [], 0
for i, c in enumerate(qs):
    if i:
        time.sleep(13)
    st, b, took = ask(c["q"])
    state = b.get("state", f"HTTP{st}")
    answer = b.get("answer", "") or ""
    docs = [x.get("documentId", "").rsplit("/", 1)[-1] for x in b.get("citations", [])]
    ok_state = state == c["state"]
    ok_doc = "doc" not in c or any(d.startswith(p) for d in docs for p in c["doc"].split("|"))
    missing = [f for f in c.get("facts", []) if not any(alt in answer for alt in f.split("|"))]
    ok = ok_state and ok_doc and not missing
    good += ok
    rows.append({"id": c["id"], "q": c["q"], "want": c["state"], "state": state, "mode": b.get("answerMode"),
                 "answerId": b.get("answerId"), "docs": docs, "missing": missing, "ok": ok,
                 "tookSec": round(took, 1), "answer": answer})
    print(f"{'OK  ' if ok else 'FAIL'} {c['id']} {state:<22} {took:4.1f}s {','.join(docs[:2]):<40} "
          f"{'빠짐:' + str(missing) if missing else ''} | {answer[:90].replace(chr(10), ' ')}")
print(f"---- 기계 채점 {good} / {len(qs)}")
if out_path:
    json.dump(rows, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
