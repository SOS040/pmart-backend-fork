"""LAN 시험용 MFC 작업자 70명 — 인사 등록(hr-service) → 계정 발급 → MFC 작업자 자격·업무 (2026-11-13 사용자 요청).

사용자: "mfc 작업자계정 너가 임의로 만들고, 30명 피패킹, 그외 분야별로 10명씩" → 전부 인사 등록, 분야는 PDA 업무 기준 4개.
  피킹·포장(PICK·PACK) 30, 입고(RECEIVE·PUTAWAY) 10, 품질검사(INSPECT) 10, 재고 이동(MOVE·REPLENISH) 10, 반품(RETURN) 10.
센터 MFC-BJG-01, 고용 형태는 정규직·알바 번갈아(알바 계약 종료 2026-12-31). 일일크루는 하루만 유효해 시험 계정으로 두지 않는다.

흐름(다시 돌려도 안전 — 있는 것은 건너뛴다):
1. hr-service 등록(인사 관리자 claude-approver) + 요청 권한 MFC_WORKER·MFC-BJG-01(소속 센터라 자동 승인) → 사서함·Keycloak 계정·안내 메일.
2. Keycloak 비밀번호를 공용 시험 비밀번호로(파일 `.secrets/keycloak-mfc-test-workers-password`, 없으면 만든다 — 출력하지 않는다).
3. mfc-capacity 가 인사 사건으로 작업자를 만들면(d46671c) 센터 관리자(claude-mfc-sup)로 자격·업무를 분야대로.
LAN 전용. 운영 금지. 토큰·비밀번호는 출력하지 않는다.

  python -X utf8 deploy/lan/seed/mfc_test_workers.py            # 전부
  python -X utf8 deploy/lan/seed/mfc_test_workers.py --check    # 상태만 보기
  python -X utf8 deploy/lan/seed/mfc_test_workers.py --center mokpo [--check]
      # 2호점 목포 MFC(MFC-MOKPO-01, R-39 2026-11-22): mfcw-mk-pp 01~13(11~13 은 2026-11-24 사용자 "피패킹 직원들 계정 3명 각 센터별로 추가")·qc·rcv 01~10(같은 공용 비밀번호) + 센터 관리자 claude-mfc-sup-mk
  python -X utf8 deploy/lan/seed/mfc_test_workers.py --center sejong [--check]
      # 3호점 세종 MFC(MFC-SEJ-01, 2026-11-23): mfcw-sj-pp 01~13·qc·rcv 01~10 + 센터 관리자 claude-mfc-sup-sj
  python -X utf8 deploy/lan/seed/mfc_test_workers.py --center chuncheon [--check]
      # 4호점 춘천 MFC(MFC-CHC-01, 2026-11-24): mfcw-cc-pp 01~13·qc·rcv 01~10 + 센터 관리자 claude-mfc-sup-cc
  python -X utf8 deploy/lan/seed/mfc_test_workers.py --center busan-namgu [--check]
      # 5호점 부산남구 MFC(MFC-BNG-01, 2026-11-24): mfcw-bn-pp 01~13·qc·rcv 01~10 + 센터 관리자 claude-mfc-sup-bn
"""
import json
import os
import secrets
import sys
import time
import urllib.parse
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "checks"))
import staff_auth  # noqa: E402

GW = staff_auth.GW
MFC = "MFC-BJG-01"
TODAY = time.strftime("%Y-%m-%d")
PART_TIME_END = "2026-12-31"
NS = uuid.UUID("6f1d0c7e-7a0b-4c1e-9d55-3b1f0a2c9e11")
PW_FILE = os.path.join(staff_auth.SECRETS, "keycloak-mfc-test-workers-password")

SUPERVISOR = "claude-mfc-sup"
LABEL = "MFC시험"
GROUPS = [  # (메일 ID 앞부분, 표시 이름, 인원, 작업 유형)
    ("pp", "피킹포장", 30, ["PICK", "PACK"]),
    ("rcv", "입고", 10, ["RECEIVE", "PUTAWAY"]),
    ("qc", "품질검사", 10, ["INSPECT"]),
    ("mv", "재고이동", 10, ["MOVE", "REPLENISH"]),
    ("rtn", "반품", 10, ["RETURN"]),
]
CENTERS = {  # --center 값 → (센터, 센터 관리자, 표시 이름 앞부분, 분야)
    "mokpo": ("MFC-MOKPO-01", "claude-mfc-sup-mk", "MFC시험 목포",
              [("mk-pp", "피킹포장", 13, ["PICK", "PACK"]), ("mk-qc", "품질검사", 10, ["INSPECT"]),
               ("mk-rcv", "입고", 10, ["RECEIVE", "PUTAWAY"])]),
    "sejong": ("MFC-SEJ-01", "claude-mfc-sup-sj", "MFC시험 세종",
               [("sj-pp", "피킹포장", 13, ["PICK", "PACK"]), ("sj-qc", "품질검사", 10, ["INSPECT"]),
                ("sj-rcv", "입고", 10, ["RECEIVE", "PUTAWAY"])]),
    "chuncheon": ("MFC-CHC-01", "claude-mfc-sup-cc", "MFC시험 춘천",
                  [("cc-pp", "피킹포장", 13, ["PICK", "PACK"]), ("cc-qc", "품질검사", 10, ["INSPECT"]),
                   ("cc-rcv", "입고", 10, ["RECEIVE", "PUTAWAY"])]),
    "busan-namgu": ("MFC-BNG-01", "claude-mfc-sup-bn", "MFC시험 부산남구",
                    [("bn-pp", "피킹포장", 13, ["PICK", "PACK"]), ("bn-qc", "품질검사", 10, ["INSPECT"]),
                     ("bn-rcv", "입고", 10, ["RECEIVE", "PUTAWAY"])]),
}


def people():
    for code, label, n, types in GROUPS:
        for i in range(1, n + 1):
            et = "REGULAR" if i % 2 else "PART_TIME"
            yield {"mailId": f"mfcw-{code}-{i:02d}", "name": f"{LABEL} {label} {i:02d}", "employmentType": et, "taskTypes": types}


def call(method, path, token, body=None, version=None, key=None):
    h = {"Authorization": "Bearer " + token}
    if method != "GET":
        h["Idempotency-Key"] = key or str(uuid.uuid4())
        h["X-Source-System"] = "claude-lan-seed"
    if version is not None:
        h["X-Expected-Version"] = str(version)
    return staff_auth._json(method, GW + path, body, h)


def shared_password():
    if not os.path.exists(PW_FILE):
        with open(PW_FILE, "w", encoding="utf-8") as f:
            f.write(secrets.token_urlsafe(18))
    return staff_auth._secret("keycloak-mfc-test-workers-password")


def find_employee(hr, mail_id):
    st, b = call("GET", "/hr/employees?q=" + urllib.parse.quote(mail_id), hr)
    items = b.get("items", b if isinstance(b, list) else []) if st == 200 else []
    for e in items:
        if e.get("mailAddress", "").split("@")[0] == mail_id:
            return e
    return None


def register(hr, p, role="MFC_WORKER"):
    e = find_employee(hr, p["mailId"])
    if e:
        return e, "있음"
    body = {"name": p["name"], "mailId": p["mailId"], "employmentType": p["employmentType"],
            "affiliation": {"type": "CENTER", "code": MFC}, "startDate": TODAY,
            "requestedGrants": [{"role": role, "centers": [MFC], "reason": "LAN 시험 " + ("작업자" if role == "MFC_WORKER" else "센터 관리자") + "(seed)"}]}
    if p["employmentType"] != "REGULAR":
        body["endDate"] = PART_TIME_END
    st, b = call("POST", "/hr/employees", hr, body, key=str(uuid.uuid5(NS, "register:" + p["mailId"])))
    if st not in (200, 201):
        raise SystemExit(f"{p['mailId']} 등록 실패: HTTP {st} {b.get('code', '')} {b.get('detail', '')}")
    return b, "등록"


def provisioning_step(e):
    pv = e.get("provisioning") or {}
    return pv.get("step") or pv.get("state") or pv.get("status")


def wait_done(hr, op, mail_id, limit=120):
    for _ in range(limit):
        st, e = call("GET", f"/hr/employees/{op}", hr)
        step = provisioning_step(e) if st == 200 else None
        if step == "DONE":
            return e
        if step in ("FAILED", "COMPENSATED"):
            raise SystemExit(f"{mail_id} 계정 발급 {step}")
        time.sleep(2)
    raise SystemExit(f"{mail_id} 계정 발급이 끝나지 않음(마지막 {step})")


def set_password(username, pw):
    admin = staff_auth._admin_token()
    st, users = staff_auth._json("GET", f"{staff_auth.KEYCLOAK}/admin/realms/{staff_auth.REALM}/users?exact=true&username="
                                 + urllib.parse.quote(username), headers={"Authorization": "Bearer " + admin})
    if st != 200 or not users:
        raise SystemExit(f"Keycloak 에 {username} 없음")
    uid = users[0]["id"]
    st, _ = staff_auth._json("PUT", f"{staff_auth.KEYCLOAK}/admin/realms/{staff_auth.REALM}/users/{uid}/reset-password",
                             {"type": "password", "value": pw, "temporary": False}, {"Authorization": "Bearer " + admin})
    if st != 204:
        raise SystemExit(f"{username} 비밀번호 설정 실패: HTTP {st}")
    u = users[0]
    if u.get("requiredActions"):
        u2 = {"requiredActions": []}
        staff_auth._json("PUT", f"{staff_auth.KEYCLOAK}/admin/realms/{staff_auth.REALM}/users/{uid}", u2,
                         {"Authorization": "Bearer " + admin})


def mfc_worker(sup, op, limit=60):
    # 목록으로 찾는다 — 상세(GET …/workers/{id})는 자격·고용 형태를 싣지 않는다(2026-11-13 발견, mfc 영역에 알림)
    for _ in range(limit):
        st, b = call("GET", f"/mfcs/{MFC}/workers", sup)
        for w in (b.get("items") or []) if st == 200 else []:
            if w["workerId"] == op:
                return w
        time.sleep(2)
    raise SystemExit(f"MFC 작업자 {op} 가 생기지 않음(인사 사건 구독 확인)")


def set_types(sup, op, types):
    w = mfc_worker(sup, op)
    q = w.get("qualification") or {}
    if sorted(q.get("taskTypes") or []) != sorted(types):
        st, b = call("POST", f"/mfcs/{MFC}/workers/{op}/qualification-changes", sup,
                     {"taskTypes": types, "reason": "LAN 시험 작업자 분야(seed)"},
                     version=q.get("qualificationVersion", 0))
        if st != 200:
            raise SystemExit(f"{op} 자격 변경 실패: HTTP {st} {b.get('code', '')} {b.get('detail', '')}")
        w = mfc_worker(sup, op)
    d = w.get("duty") or {}
    if sorted(d.get("taskTypes") or []) != sorted(types):
        st, b = call("POST", f"/mfcs/{MFC}/workers/{op}/duty-changes", sup,
                     {"taskTypes": types, "reason": "LAN 시험 작업자 분야(seed)"}, version=d.get("dutyVersion", 0))
        if st != 200:
            raise SystemExit(f"{op} 업무 변경 실패: HTTP {st} {b.get('code', '')} {b.get('detail', '')}")


def supervisor(hr):
    """2호점 등 — 센터 관리자 계정이 아직 없으면 인사 등록(MFC_SUPERVISOR·그 센터)·발급. 비밀번호는 staff_auth 가 자기 파일로."""
    p = {"mailId": SUPERVISOR, "name": f"{LABEL} 센터관리자", "employmentType": "REGULAR"}
    e, how = register(hr, p, role="MFC_SUPERVISOR")
    wait_done(hr, e["operatorId"], SUPERVISOR)
    print(f"센터 관리자 {SUPERVISOR} {e['operatorId']} ({how})", flush=True)


def main():
    global MFC, SUPERVISOR, LABEL, GROUPS
    check = "--check" in sys.argv
    hr, _ = staff_auth.staff_token("claude-approver")
    if "--center" in sys.argv:
        MFC, SUPERVISOR, LABEL, GROUPS = CENTERS[sys.argv[sys.argv.index("--center") + 1]]
        if not check:
            supervisor(hr)
    sup, _ = staff_auth.staff_token(SUPERVISOR, ["MFC_SUPERVISOR"], [MFC])
    pw = None if check else shared_password()
    counts = {}
    for p in people():
        if check:
            e = find_employee(hr, p["mailId"])
            print(p["mailId"], "없음" if not e else f"{e['operatorId']} {provisioning_step(e)} {e['status']}")
            continue
        e, how = register(hr, p)
        op = e["operatorId"]
        e = wait_done(hr, op, p["mailId"])
        set_password(e["mailAddress"], pw)
        set_types(sup, op, p["taskTypes"])
        counts[how] = counts.get(how, 0) + 1
        print(f"OK {p['mailId']} {op} {p['employmentType']} {'+'.join(p['taskTypes'])} ({how})", flush=True)
    if not check:
        first = next(people())["mailId"]
        tok, op = staff_auth._exchange(first + "@pmart.com", pw)
        print(f"로그인 확인 {first}:", "OK" if tok and op else "실패")
        print("요약:", json.dumps(counts, ensure_ascii=False))


if __name__ == "__main__":
    main()
