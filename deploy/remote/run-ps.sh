#!/bin/sh
# PC 1 → 원격 Windows PC(pmart-pc2~5, pmart-pc1 = 이 PC 는 SSH 없이)에서 PowerShell 스크립트 실행.
# 사용: sh deploy/remote/run-ps.sh <ssh 별칭> <script.ps1>   (stdin은 원격 스크립트로 전달)
# 스크립트를 UTF-16LE base64로 인코딩해 -EncodedCommand로 보낸다 → 셸 따옴표 문제 없음.
# 접속 별칭·키는 ~/.ssh/config (docs/worklog/2026-10-24.md 참조).
set -eu
case "$1" in pmart-pc[0-9]) ;; *) echo "unknown host alias: $1" >&2; exit 2 ;; esac
enc=$( (printf '[Console]::OutputEncoding=[Text.Encoding]::UTF8\n'; cat "$2") | iconv -f UTF-8 -t UTF-16LE | base64 -w0)
# 종료 코드 = ssh(원격 PowerShell)의 종료 코드. sh 에는 pipefail 이 없어 파이프 끝(grep)의 코드가 나가던 것을 파일로 넘긴다
# (2026-11-03: 호스트 이름 해석 실패로 아무것도 배포되지 않았는데 lan.sh deploy 가 0 으로 끝남)
rcf=$(mktemp)
# pmart-pc1 = 이 PC(배포 PC 자신, 2026-11-15 — 감시 묶음 배치). SSH 서버가 없으니 같은 스크립트를 이 PC 의 PowerShell 로 바로 실행한다
if [ "$1" = pmart-pc1 ]; then
  { powershell -NoProfile -NonInteractive -EncodedCommand "$enc" 2>&1; echo $? > "$rcf"; } | grep -v -E '^#< CLIXML|^<Objs' || true
  rc=$(cat "$rcf"); rm -f "$rcf"
  exit "${rc:-1}"
fi
# 컴퓨터 이름은 DNS 가 아니라 LLMNR/NetBIOS(방송)로만 찾는다 — 상대 PC 가 바쁘면(배포 중) 가끔 "Could not resolve hostname" 으로 실패한다
# (2026-11-15 두 번). 이름을 못 찾았으면 원격에서 아무것도 돌지 않았으므로 3초 쉬고 다시(최대 3번). 그 밖의 실패는 다시 하지 않는다
out=$(mktemp)
for attempt in 1 2 3; do
  { ssh -o BatchMode=yes -o ConnectTimeout=10 "$1" "powershell -NoProfile -NonInteractive -EncodedCommand $enc" 2>&1; echo $? > "$rcf"; } \
    | tee "$out" | grep -v -E 'post-quantum|store now|pq.html|^#< CLIXML|^<Objs|Could not resolve hostname' || true
  rc=$(cat "$rcf")
  if [ "$rc" = 255 ] && grep -q 'Could not resolve hostname' "$out" && [ "$attempt" -lt 3 ]; then
    echo "($1 이름 해석 실패 — 3초 뒤 다시 $((attempt + 1))/3)" >&2; sleep 3; continue
  fi
  grep -q 'Could not resolve hostname' "$out" && grep 'Could not resolve hostname' "$out" >&2
  break
done
rm -f "$rcf" "$out"
exit "${rc:-1}"
