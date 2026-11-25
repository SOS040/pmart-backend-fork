"""LAN 시험용 칠곡 DC(CDC01) 계정 — 센터 관리자 1 + 작업자 28 (2026-11-25 cdc 세션 R-16, 사용자 "그대로 진행해").

- 센터 관리자 `claude-cdc-sup` — 역할 CDC_SUPERVISOR·센터 CDC01(비밀번호는 staff_auth 가 자기 파일 `.secrets/keycloak-claude-cdc-sup-password`).
- 작업자(역할 CDC_WORKER·센터 CDC01, 공용 시험 비밀번호 `.secrets/keycloak-cdc-test-workers-password` — 없으면 만든다, 출력하지 않는다):
  cdcw-fc-01~10(FC 피킹) · cdcw-dl-01~05(분배·라인) · cdcw-qc-01~05(검품) · cdcw-ld-01~05(상차) · cdcw-fk-01~03(지게차)
- 소속 = 센터 CDC01. 고용 형태 정규직·알바 번갈아(알바 계약 종료 2026-12-31).

흐름(다시 돌려도 안전 — 있는 것은 건너뛴다):
1. hr-service 등록(인사 관리자 claude-approver) — 요청 권한 CDC_WORKER/CDC_SUPERVISOR·CDC01 → 사서함·Keycloak 계정.
2. Keycloak 비밀번호를 공용 시험 비밀번호로(작업자).
3. employee-auth 역할·센터를 맞춘다(claude-dev 관리자 — CDC 역할은 자동 승인 규칙이 없어 요청 권한이 심사 대기일 수 있다).
CDC 서비스는 지금 역할을 보지 않고 직원 토큰·centers 만 본다. LAN 전용. 토큰·비밀번호는 출력하지 않는다.

  python -I -X utf8 deploy/lan/seed/cdc_test_workers.py           # 전부
  python -I -X utf8 deploy/lan/seed/cdc_test_workers.py --check   # 상태만
"""
import os
import secrets
import sys
import time
import urllib.parse
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "checks"))
import staff_auth  # noqa: E402

GW = staff_auth.GW
CDC = "CDC01"
TODAY = time.strftime("%Y-%m-%d")
PART_TIME_END = "2026-12-31"
NS = uuid.UUID("0c7a2b51-4f0e-4f7e-9b8a-cdc01aa0b161")
PW_FILE = os.path.join(staff_auth.SECRETS, "keycloak-cdc-test-workers-password")
SUPERVISOR = "claude-cdc-sup"
LABEL = "CDC시험 칠곡"
GROUPS = [  # (메일 ID 앞부분, 표시 이름, 인원)
    ("fc", "FC", 10),
    ("dl", "분배라인", 5),
    ("qc", "검품", 5),
    ("ld", "상차", 5),
    ("fk", "지게차", 3),
]


def people():
    for code, label, n in GROUPS:
        for i in range(1, n + 1):
            yield {"mailId": f"cdcw-{code}-{i:02d}", "name": f"{LABEL} {label} {i:02d}",
                   "employmentType": "REGULAR" if i % 2 else "PART_TIME"}


def call(method, path, token, body=None, key=None):
    h = {"Authorization": "Bearer " + token}
    if method != "GET":
        h["Idempotency-Key"] = key or str(uuid.uuid4())
        h["X-Source-System"] = "claude-lan-seed"
    return staff_auth._json(method, GW + path, body, h)


def shared_password():
    if not os.path.exists(PW_FILE):
        with open(PW_FILE, "w", encoding="utf-8") as f:
            f.write(secrets.token_urlsafe(18))
    return staff_auth._secret("keycloak-cdc-test-workers-password")


def find_employee(hr, mail_id):
    st, b = call("GET", "/hr/employees?q=" + urllib.parse.quote(mail_id), hr)
    items = b.get("items", b if isinstance(b, list) else []) if st == 200 else []
    for e in items:
        if e.get("mailAddress", "").split("@")[0] == mail_id:
            return e
    return None


def register(hr, p, role):
    e = find_employee(hr, p["mailId"])
    if e:
        return e, "있음"
    body = {"name": p["name"], "mailId": p["mailId"], "employmentType": p["employmentType"],
            "affiliation": {"type": "CENTER", "code": CDC}, "startDate": TODAY,
            "requestedGrants": [{"role": role, "centers": [CDC], "reason": "LAN 시험 CDC 계정(R-16 seed)"}]}
    if p["employmentType"] != "REGULAR":
        body["endDate"] = PART_TIME_END
    st, b = call("POST", "/hr/employees", hr, body, key=str(uuid.uuid5(NS, "register:" + p["mailId"])))
    if st not in (200, 201):
        raise SystemExit(f"{p['mailId']} 등록 실패: HTTP {st} {b.get('code', '')} {b.get('detail', '')}")
    return b, "등록"


def wait_done(hr, op, mail_id, limit=120):
    step = None
    for _ in range(limit):
        st, e = call("GET", f"/hr/employees/{op}", hr)
        pv = (e.get("provisioning") or {}) if st == 200 else {}
        step = pv.get("step") or pv.get("state") or pv.get("status")
        if step == "DONE":
            return e
        if step in ("FAILED", "COMPENSATED"):
            raise SystemExit(f"{mail_id} 계정 발급 {step}")
        time.sleep(2)
    raise SystemExit(f"{mail_id} 계정 발급이 끝나지 않음(마지막 {step})")


def set_password(username, pw):
    admin = staff_auth._admin_token()
    st, users = staff_auth._json("GET", f"{staff_auth.KEYCLOAK}/admin/realms/{staff_auth.REALM}/users?exact=true&username="
                                 + urllib.parse.quote(staff_auth.kc_username(username)),
                                 headers={"Authorization": "Bearer " + admin})
    if st != 200 or not users:
        raise SystemExit(f"Keycloak 에 {username} 없음")
    uid = users[0]["id"]
    st, _ = staff_auth._json("PUT", f"{staff_auth.KEYCLOAK}/admin/realms/{staff_auth.REALM}/users/{uid}/reset-password",
                             {"type": "password", "value": pw, "temporary": False}, {"Authorization": "Bearer " + admin})
    if st != 204:
        raise SystemExit(f"{username} 비밀번호 설정 실패: HTTP {st}")
    if users[0].get("requiredActions"):
        staff_auth._json("PUT", f"{staff_auth.KEYCLOAK}/admin/realms/{staff_auth.REALM}/users/{uid}",
                         {"requiredActions": []}, {"Authorization": "Bearer " + admin})


def set_roles(op, roles, centers):
    """employee-auth 직원 역할·센터를 맞춘다(claude-dev 관리자 — staff_auth.staff_token 과 같은 방식). 바꿨으면 True."""
    admin, _ = staff_auth._exchange("claude-dev", staff_auth._password("claude-dev"))
    auth = {"Authorization": "Bearer " + admin}
    for _ in range(30):  # 발급 DONE 직후 employee-auth 에 아직 없을 수 있다(404) — 잠깐 기다린다
        st, cur = staff_auth._json("GET", f"{GW}/employee-auth/employees/{op}", headers=auth)
        if st != 404:
            break
        time.sleep(2)
    if st != 200:
        raise SystemExit(f"{op} 직원 조회 실패: HTTP {st}")
    if sorted(cur["roles"]) == sorted(roles) and sorted(cur["centers"]) == sorted(centers) and cur["state"] == "ACTIVE":
        return False
    st, b = staff_auth._json("PUT", f"{GW}/employee-auth/employees/{op}",
                             {"state": "ACTIVE", "roles": sorted(roles), "centers": sorted(centers),
                              "reason": "LAN 시험 CDC 계정 역할(R-16 seed)"},
                             {**auth, "Idempotency-Key": str(uuid.uuid4()), "X-Source-System": "claude-lan-seed"})
    if st != 200:
        raise SystemExit(f"{op} 역할 맞춤 실패: HTTP {st} {b.get('code', '')} {b.get('detail', '')}")
    return True


def main():
    check = "--check" in sys.argv
    hr, _ = staff_auth.staff_token("claude-approver")
    pw = None if check else shared_password()
    rows = [({"mailId": SUPERVISOR, "name": f"{LABEL} 센터관리자", "employmentType": "REGULAR"}, "CDC_SUPERVISOR")]
    rows += [(p, "CDC_WORKER") for p in people()]
    done = 0
    for p, role in rows:
        if check:
            e = find_employee(hr, p["mailId"])
            print(f"{p['mailId']}: {'있음 ' + e['operatorId'] if e else '없음'}", flush=True)
            continue
        e, how = register(hr, p, role)
        op = e["operatorId"]
        wait_done(hr, op, p["mailId"])
        if role == "CDC_WORKER":
            set_password(p["mailId"], pw)
        changed = set_roles(op, [role], [CDC])
        done += 1
        print(f"{p['mailId']} {op} ({how}{', 역할 맞춤' if changed else ''})", flush=True)
    if not check:
        # 센터 관리자 비밀번호는 staff_auth 가 자기 파일로(없으면 새로 정해 저장) — 로그인 확인 겸
        staff_auth.staff_token(SUPERVISOR, ["CDC_SUPERVISOR"], [CDC])
        print(f"끝 — {done}개 계정 ACTIVE·CDC01", flush=True)


if __name__ == "__main__":
    main()
