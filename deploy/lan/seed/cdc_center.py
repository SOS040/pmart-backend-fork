"""CM-3 칠곡 DC `CDC01` 을 center-master 에 등록한다(2026-11-24 cdc 요청, 값은 docs/simulator/cdc/chilgok-dc.md §1).

center-master CENTER_MASTER 승인 흐름(Seed.java center() 와 같음): seed-md 변경 요청·제출 → seed-approver 승인.
있고 값이 같으면 건너뛴다. 면적·보관 용량은 center-master 에 칸이 없어 넣지 않는다(시뮬레이터 표시값).
좌표 = 카카오 주소 검색(2026-11-25, 도로명 그대로 찾음).

  python -I deploy/lan/seed/cdc_center.py            # 계획만(지금 상태)
  python -I deploy/lan/seed/cdc_center.py --apply    # 등록
  PMART_GATEWAY=http://localhost:8080/api/v1 ... 로컬(로컬 토큰 경로가 다르면 Seed.java 처럼 dev-tokens 사용)
"""
import json
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "checks"))
import staff_auth  # noqa: E402

CENTER_ID = "CDC01"
PROFILE = {
    "type": "CDC",
    "name": "칠곡 중앙물류센터(칠곡 DC)",
    "state": "ACTIVE",
    "address": "경상북도 칠곡군 지천면 산단터널로 240",
    "coordinates": {"lat": 35.954308, "lng": 128.442821},
    "temperatureZones": ["AMBIENT", "CHILLED", "FROZEN"],
    "floors": 3,
}
GW = staff_auth.GW


def call(method, path, token, body=None, version=None):
    h = {"Authorization": "Bearer " + token}
    if method != "GET":
        h.update({"Idempotency-Key": str(uuid.uuid4()), "X-Source-System": "pmart-seed"})
    if version is not None:
        h["X-Expected-Version"] = str(version)
    return staff_auth._json(method, GW + path, body, h)


def same(cur):
    return all(cur.get(k) == PROFILE[k] for k in ("type", "name", "address", "floors")) \
        and sorted(cur.get("temperatureZones") or []) == sorted(PROFILE["temperatureZones"]) \
        and cur.get("coordinates") == PROFILE["coordinates"]


def main():
    apply = "--apply" in sys.argv
    md, _ = staff_auth.staff_token("seed-md")
    st, cur = call("GET", f"/centers/{CENTER_ID}", md)
    if st == 200 and same(cur):
        print(f"{CENTER_ID}: 있음(값 같음) → 건너뜀")
        return
    if st == 200:
        print(f"{CENTER_ID}: 있음, 값 다름 — 손으로 확인: {json.dumps(cur, ensure_ascii=False)}")
        return
    print(f"{CENTER_ID}: 없음(HTTP {st}) → CREATE {json.dumps(PROFILE, ensure_ascii=False)}")
    if not apply:
        print("계획만 — --apply 로 등록")
        return
    approver, _ = staff_auth.staff_token("seed-approver")
    body = {
        "type": "CENTER_MASTER",
        "targetService": "center-master-service",
        "targetRef": f"CENTER-{CENTER_ID}-{int(time.time() * 1000)}",
        "allowedActions": ["APPLY_CENTER_CHANGE"],
        "summary": "CM-3 칠곡 DC CDC01 생성",
        "lines": [{"lineKey": f"{CENTER_ID}:CREATE",
                   "attributes": {"changeType": "CREATE", "centerId": CENTER_ID, "profile": PROFILE}}],
    }
    st, cr = call("POST", "/change-requests", md, body)
    if st != 201:
        raise SystemExit(f"변경 요청 실패 HTTP {st} {cr}")
    st, sub = call("POST", f"/change-requests/{cr['changeRequestId']}/submissions", md, None, cr["version"])
    if st not in (200, 202):
        raise SystemExit(f"제출 실패 HTTP {st} {sub}")
    st, dec = call("POST", f"/approvals/{sub['resultId']}/decisions", approver,
                   {"decision": "APPROVE", "comment": "CM-3 칠곡 DC 등록(pmart-seed)"})
    if st not in (200, 201, 202):
        raise SystemExit(f"승인 실패 HTTP {st} {dec}")
    print(f"승인 {sub['resultId']}")
    for _ in range(30):
        st, cur = call("GET", f"/centers/{CENTER_ID}", md)
        if st == 200:
            print(f"반영: {json.dumps(cur, ensure_ascii=False)}")
            return
        time.sleep(2)
    raise SystemExit("60초 안에 반영 안 됨 — 승인 상태 확인")


if __name__ == "__main__":
    main()
