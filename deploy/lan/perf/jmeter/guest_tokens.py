"""JMeter 부하 시험용 비회원 토큰 N개 받기(2026-11-26, 계획 docs/operations/2026-11-26-jmeter-load-test-plan.md).

게이트웨이 공식 경로 POST /auth/guest-sessions(기기마다 새 비회원 주체 GST-…, 개인정보 없음, access 1시간)로 받는다.
로그인 화면·소셜 로그인을 거치지 않을 뿐 인증은 그대로다(게이트웨이 검증 끄기 없음). 계정을 새로 만들지 않는다.

  python -I -X utf8 deploy/lan/perf/jmeter/guest_tokens.py 100      # → deploy/lan/perf/jmeter/out/tokens.csv

out/ 은 git 제외(.gitignore out/). 토큰을 화면·로그에 찍지 않는다. 1시간 안에 시험을 끝낸다(지나면 다시 받는다).
익명 처리율 제한(IP 10/s·버스트 20)에 걸리지 않게 0.15초 간격으로 받는다.
"""

import csv
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
GW = os.environ.get("PMART_GW", "https://192.168.0.40:8443/api/v1")
CA = os.path.join(HERE, "..", "..", "tls", "pmart-lan-root-ca.crt")


def main() -> int:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    ctx = ssl.create_default_context(cafile=CA) if os.path.exists(CA) else ssl.create_default_context()
    out_dir = os.path.join(HERE, "out")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "tokens.csv")
    run = time.strftime("%Y%m%d%H%M%S")
    ok = 0
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        for i in range(n):
            device = f"pmart-perf-{run}-{i:03d}"
            req = urllib.request.Request(
                GW + "/auth/guest-sessions",
                data=b"{}",
                method="POST",
                headers={
                    "Content-Type": "application/json",
                    "X-Device-Id": device,
                    "X-Source-System": "perf-jmeter",
                    "Idempotency-Key": str(uuid.uuid4()),
                },
            )
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(req, context=ctx, timeout=20) as r:
                        b = json.loads(r.read())
                    w.writerow([device, b["tokens"]["accessToken"]])
                    ok += 1
                    break
                except urllib.error.HTTPError as e:
                    if e.code == 429 and attempt < 2:
                        time.sleep(2)
                        continue
                    print(f"{i}: HTTP {e.code}", file=sys.stderr)
                    break
            time.sleep(0.15)
    print(f"비회원 토큰 {ok}/{n} → {path} (1시간 유효)")
    return 0 if ok == n else 1


if __name__ == "__main__":
    sys.exit(main())
