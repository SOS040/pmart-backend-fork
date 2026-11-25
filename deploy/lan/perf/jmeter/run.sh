#!/bin/sh
# JMeter 비회원 부하 시험 단계 실행(2026-11-26, 계획 docs/operations/2026-11-26-jmeter-load-test-plan.md).
#   sh deploy/lan/perf/jmeter/run.sh            # 10 → 50 → 100명, 단계마다 5분
#   sh deploy/lan/perf/jmeter/run.sh 10         # 한 단계만(인원)
#   DURATION=60 sh deploy/lan/perf/jmeter/run.sh 10   # 짧은 확인
# 먼저: python -I -X utf8 deploy/lan/perf/jmeter/guest_tokens.py 100 (토큰 1시간 유효)
# 결과: deploy/lan/perf/jmeter/out/<시각>-u<인원>/{results.jtl, report/index.html}
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
JMETER=${JMETER:-/c/tools/apache-jmeter-5.6.3/bin/jmeter.bat}
DURATION=${DURATION:-300}
RAMP=${RAMP:-60}
STAGES=${*:-10 50 100}

[ -f "$HERE/out/tokens.csv" ] || { echo "토큰 없음 — guest_tokens.py 먼저" >&2; exit 1; }
free=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)")
[ "$free" -ge 4096 ] || { echo "남은 메모리 ${free}MB < 4GB — 미룬다(CLAUDE.md)" >&2; exit 1; }

cd "$HERE"
for u in $STAGES; do
  d="out/$(date +%Y%m%d-%H%M%S)-u$u"
  mkdir -p "$d"
  echo "== $u명 · ${DURATION}s (램프 ${RAMP}s) → $d"
  HEAP="-Xms512m -Xmx2g" "$JMETER" -n -t pmart-guest.jmx \
    -Jusers="$u" -Jramp="$RAMP" -Jduration="$DURATION" \
    -Jtokens=out/tokens.csv -Jkeywords=keywords.csv \
    -l "$d/results.jtl" -e -o "$d/report" -j "$d/jmeter.log" | tail -5
  # 멈춤 기준: 오류율 1% 초과 또는 p95 2초 초과면 다음 단계로 가지 않는다
  python -I -X utf8 "$HERE/summary.py" "$d/results.jtl" || { echo "멈춤 기준을 넘어 다음 단계를 건너뜀" >&2; exit 2; }
done
