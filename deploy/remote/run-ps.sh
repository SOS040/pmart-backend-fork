#!/bin/sh
# PC 1 → 원격 Windows PC(pmart-pc2/3/4)에서 PowerShell 스크립트 실행.
# 사용: sh deploy/remote/run-ps.sh <ssh 별칭> <script.ps1>   (stdin은 원격 스크립트로 전달)
# 스크립트를 UTF-16LE base64로 인코딩해 -EncodedCommand로 보낸다 → 셸 따옴표 문제 없음.
# 접속 별칭·키는 ~/.ssh/config (docs/worklog/2026-10-24.md 참조).
set -eu
case "$1" in pmart-pc[0-9]) ;; *) echo "unknown host alias: $1" >&2; exit 2 ;; esac
enc=$( (printf '[Console]::OutputEncoding=[Text.Encoding]::UTF8\n'; cat "$2") | iconv -f UTF-8 -t UTF-16LE | base64 -w0)
ssh -o BatchMode=yes -o ConnectTimeout=10 "$1" "powershell -NoProfile -NonInteractive -EncodedCommand $enc" 2>&1 \
  | grep -v -E 'post-quantum|store now|pq.html|^#< CLIXML|^<Objs'
