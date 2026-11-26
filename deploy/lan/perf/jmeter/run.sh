#!/bin/sh
# JMeter 비회원 부하 시험 단계 실행(2026-11-26, 계획 docs/operations/2026-11-26-jmeter-load-test-plan.md).
#   sh deploy/lan/perf/jmeter/run.sh            # 10 → 50 → 100명, 단계마다 5분
#   sh deploy/lan/perf/jmeter/run.sh 10         # 한 단계만(인원)
#   DURATION=60 sh deploy/lan/perf/jmeter/run.sh 10   # 짧은 확인
# 먼저: python -I -X utf8 deploy/lan/perf/jmeter/guest_tokens.py 100 (토큰 1시간 유효)
# 결과: $OUT_DIR/<시각>-u<인원>/{results.jtl, report/index.html, jmeter.log}
#   OUT_DIR 기본 /d/pmart-perf/jmeter(D 드라이브 — C 는 여유가 적다, 사용자 2026-11-26). 토큰은 그대로 저장소 out/(git 제외)
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
JMETER=${JMETER:-/c/tools/apache-jmeter-5.6.3/bin/jmeter.bat}
DURATION=${DURATION:-300}
RAMP=${RAMP:-60}
THINK_MIN=${THINK_MIN:-1000}     # 생각 시간 최소 ms(한계 찾기 2026-11-27)
THINK_RANGE=${THINK_RANGE:-2000} # 더하는 무작위 폭 ms
STAGES=${*:-10 50 100}
OUT_DIR=${OUT_DIR:-/d/pmart-perf/jmeter}
mkdir -p "$OUT_DIR" || { echo "결과 폴더 $OUT_DIR 를 만들 수 없음 — OUT_DIR 로 바꾸세요" >&2; exit 1; }

[ -f "$HERE/out/tokens.csv" ] || { echo "토큰 없음 — guest_tokens.py 먼저" >&2; exit 1; }
free=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)")
[ "$free" -ge 4096 ] || { echo "남은 메모리 ${free}MB < 4GB — 미룬다(CLAUDE.md)" >&2; exit 1; }

cd "$HERE"
for u in $STAGES; do
  d="$OUT_DIR/$(date +%Y%m%d-%H%M%S)-u$u"
  mkdir -p "$d"
  echo "== $u명 · ${DURATION}s (램프 ${RAMP}s, 생각 ${THINK_MIN}+0~${THINK_RANGE}ms) → $d"
  HEAP="-Xms512m -Xmx2g" "$JMETER" -n -t pmart-guest.jmx \
    -Jusers="$u" -Jramp="$RAMP" -Jduration="$DURATION" -JthinkMin="$THINK_MIN" -JthinkRange="$THINK_RANGE" \
    -Jtokens=out/tokens.csv -Jkeywords=keywords.csv \
    -l "$d/results.jtl" -e -o "$d/report" -j "$d/jmeter.log" | tail -5
  # 멈춤 기준: 오류율 1% 초과 또는 p95 2초 초과면 다음 단계로 가지 않는다
  python -I -X utf8 "$HERE/summary.py" "$d/results.jtl" || { echo "멈춤 기준을 넘어 다음 단계를 건너뜀" >&2; exit 2; }
done
