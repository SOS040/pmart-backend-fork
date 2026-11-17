"""LAN 시험용 직원 토큰 — employee-auth-service(ADR-0056). auth-service 운영자 로그인을 없앤 뒤(2026-11-13) 시험 스크립트가 같이 쓴다.

흐름: Keycloak 비밀번호 로그인(개발 클라이언트 pmart-dev-cli, LAN 전용) → employee-auth-service 시험용 토큰 교환
(`/api/v1/internal/dev-token-exchange` — 게이트웨이에 없음, 같은 직원 상태 확인을 거친다) → PMart 직원 토큰(15분).

- `claude-dev`(OPERATOR_ADMIN): 비밀번호 `.secrets/keycloak-claude-dev-password`.
- 다른 시험 계정: `.secrets/keycloak-<계정>-password` 가 있으면 그 값, 없으면 Keycloak 관리 API 로 무작위 비밀번호를 새로 정하고
  **그 파일에 저장한다**(2026-11-18 — 전에는 저장하지 않아 실행마다 비밀번호가 바뀌어 다른 PC 파일이 무효가 되고, 옛 값 로그인이 쌓여
  계정이 잠겼다). 새로 정하는 것은 `.secrets/keycloak-admin-password` 가 있는 PC 1 뿐 — 다른 PC 는 PC 1 에서 그 파일을 받는다. 출력하지 않는다.
- 역할·센터(`roles`·`centers`)를 주면 employee-auth-service 관리 경로(관리자 claude-dev)로 맞춘 뒤 토큰을 받는다.
- 계정은 만들지 않는다 — 새 직원은 hr-service 로 등록한다(Keycloak 에 없으면 멈춘다).
토큰·비밀번호는 출력하지 않는다.
"""
import json
import os
import secrets
import urllib.error
import urllib.parse
import urllib.request
import uuid

SECRETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".secrets")
KEYCLOAK = os.environ.get("PMART_KEYCLOAK", "http://192.168.0.101:8180")
EMPLOYEE_AUTH = os.environ.get("PMART_EMPLOYEE_AUTH", "http://192.168.0.101:8511")
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
REALM = "pmart-staff"
MAIL_DOMAIN = "@pmart.com"


def kc_username(name):
    """Keycloak 아이디 = 사내 메일 전체(ADR-0056 결정 7, 2026-11-15 짧은 아이디 13개도 바꿈). 짧게 주면 @pmart.com 을 붙인다 —
    브라우저 로그인 화면은 테마 스크립트가 붙이지만 이 스크립트는 화면을 거치지 않는다. 비밀번호 파일 이름은 짧은 이름 그대로."""
    return name if "@" in name else name + MAIL_DOMAIN


def _secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as f:
        return f.read().strip()


def _json(method, url, body=None, headers=None, form=None):
    h = {"Accept": "application/json"}
    h.update(headers or {})
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
    elif body is not None:
        h["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


def _admin_token():
    st, b = _json("POST", f"{KEYCLOAK}/realms/master/protocol/openid-connect/token",
                  form={"grant_type": "password", "client_id": "admin-cli", "username": "admin",
                        "password": _secret("keycloak-admin-password")})
    if st != 200:
        raise SystemExit(f"Keycloak 관리자 로그인 실패: HTTP {st}")
    return b["access_token"]


def _password(username):
    if username == "claude-dev":
        return _secret("keycloak-claude-dev-password")
    path = os.path.join(SECRETS, f"keycloak-{username}-password")
    if os.path.exists(path):
        return _secret(f"keycloak-{username}-password")
    if not os.path.exists(os.path.join(SECRETS, "keycloak-admin-password")):
        raise SystemExit(f"{os.path.basename(path)} 파일이 없다 — PC 1 에서 받아 .secrets 에 둔다(새로 정하기는 PC 1 만)")
    admin = _admin_token()
    st, users = _json("GET", f"{KEYCLOAK}/admin/realms/{REALM}/users?exact=true&username={urllib.parse.quote(kc_username(username))}",
                      headers={"Authorization": "Bearer " + admin})
    if st != 200 or not users:
        raise SystemExit(f"Keycloak 에 {username} 이 없다 — 새 직원은 hr-service 로 등록한다")
    pw = secrets.token_urlsafe(18)
    st, _ = _json("PUT", f"{KEYCLOAK}/admin/realms/{REALM}/users/{users[0]['id']}/reset-password",
                  {"type": "password", "value": pw, "temporary": False}, {"Authorization": "Bearer " + admin})
    if st != 204:
        raise SystemExit(f"{username} 비밀번호 설정 실패: HTTP {st}")
    # 바꾼 값을 바로 저장한다 — 다음 실행·다른 PC 가 같은 값을 쓴다. 이미 생긴 파일은 덮지 않는다(동시 실행 대비).
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise SystemExit(f"{username} 비밀번호 파일이 실행 중에 생겼다 — 다시 실행한다(Keycloak 값과 다를 수 있음)")
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
        f.write(pw)
    print(f"  ..   {username} 비밀번호를 새로 정해 .secrets/{os.path.basename(path)} 에 저장 - 다른 PC 는 이 파일을 받는다")
    return pw


def _exchange(username, password):
    st, kc = _json("POST", f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
                   form={"grant_type": "password", "client_id": "pmart-dev-cli", "username": kc_username(username), "password": password})
    if st != 200:
        raise SystemExit(f"{username} Keycloak 로그인 실패: HTTP {st} {kc.get('error', '')}")
    st, b = _json("POST", f"{EMPLOYEE_AUTH}/api/v1/internal/dev-token-exchange", {"keycloakAccessToken": kc["access_token"]},
                  {"Idempotency-Key": str(uuid.uuid4()), "X-Source-System": "claude-lan-check", "X-Device-Id": "claude-lan-check"})
    if st != 200:
        raise SystemExit(f"{username} 직원 토큰 교환 실패: HTTP {st} {b.get('code', '')}")
    return b["tokens"]["accessToken"] if "tokens" in b else b["accessToken"], b.get("operatorId")


def staff_token(username, roles=None, centers=None):
    """(access token, 운영자 ID). roles·centers 를 주면 그 값으로 맞춘다(claude-dev 자신은 바꾸지 않음)."""
    password = _password(username)
    token, op = _exchange(username, password)
    if roles is None or username == "claude-dev":
        return token, op
    admin, _ = _exchange("claude-dev", _password("claude-dev"))
    auth = {"Authorization": "Bearer " + admin}
    st, cur = _json("GET", f"{GW}/employee-auth/employees/{op}", headers=auth)
    if st != 200:
        raise SystemExit(f"{username} 직원 조회 실패: HTTP {st}")
    want_roles, want_centers = sorted(roles), sorted(centers or [])
    if sorted(cur["roles"]) != want_roles or sorted(cur["centers"]) != want_centers or cur["state"] != "ACTIVE":
        st, b = _json("PUT", f"{GW}/employee-auth/employees/{op}",
                      {"state": "ACTIVE", "roles": want_roles, "centers": want_centers, "reason": "LAN 시험 계정 역할 맞춤(시험 스크립트)"},
                      {**auth, "Idempotency-Key": str(uuid.uuid4()), "X-Source-System": "claude-lan-check"})
        if st != 200:
            raise SystemExit(f"{username} 역할 맞춤 실패: HTTP {st} {b.get('code', '')}")
        token, op = _exchange(username, password)
    return token, op


def local_staff_token(operator_id, roles, centers=None, employee_auth="http://localhost:8511"):
    """로컬(local 프로필) 개발용 — Keycloak 없이 employee-auth-service 의 dev-token-mint(POST /api/v1/internal/dev-tokens).
    계정이 없으면 만들고 역할·센터를 이 값으로 맞춘다. 로컬 전용(LAN·운영에는 경로가 없다 → 404). (access token, 운영자 ID)."""
    st, b = _json("POST", f"{employee_auth}/api/v1/internal/dev-tokens",
                  {"operatorId": operator_id, "roles": list(roles), "centers": list(centers or [])},
                  {"Idempotency-Key": str(uuid.uuid4()), "X-Source-System": "local-dev", "X-Device-Id": "local-dev"})
    if st != 200:
        raise SystemExit(f"로컬 직원 토큰 발급 실패: HTTP {st} {b.get('code', '')} — local 프로필 employee-auth-service 가 떠 있나?")
    return b["tokens"]["accessToken"], b.get("operatorId")
