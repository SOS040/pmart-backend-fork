#!/usr/bin/env bash
# LAN PC 배포 실행기 (PC 1 Git Bash 에서). 배치는 placement.conf, 파일 생성은 render.sh.
#   deploy/lan/lan.sh render                      비밀정보 준비 + PC별 파일 생성(build/)
#   deploy/lan/lan.sh deploy <pc|all> [서비스...]  파일 복사 후 docker compose up -d (서비스 지정 시 그것만)
#   deploy/lan/lan.sh status <pc|all>             컨테이너 상태
#   deploy/lan/lan.sh logs <pc> <서비스> [줄수]     최근 로그(기본 100줄)
#   deploy/lan/lan.sh restart <pc> <서비스...>
#   deploy/lan/lan.sh down <pc|all>               컨테이너 정지·삭제(볼륨은 남긴다)
# 원격 실행: deploy/remote/run-ps.sh (원격 docker 는 %USERPROFILE%\.docker-pmart 설정 — 읽기 전용 pull 토큰)
# 원격 작업 폴더: %USERPROFILE%\pmart-lan. PMart 이외 컨테이너는 건드리지 않는다(compose 프로젝트 pmart-lan 만).
set -euo pipefail
cd "$(dirname "$0")/../.."
LAN=deploy/lan
OUT=$LAN/build
TMP="${TMPDIR:-${TEMP:-/tmp}}/pmart-lan"; mkdir -p "$TMP"

pcs() { if [ "$1" = all ]; then ls "$OUT"; else [ -d "$OUT/$1" ] || { echo "PC 없음: $1 (render 했는지 확인)" >&2; exit 1; }; echo "$1"; fi; }
alias_of() { awk '{print $3}' "$OUT/$1/target"; }

# 원격에서 docker compose <args> 실행
remote_compose() {
  local pc="$1"; shift
  cat > "$TMP/$pc.ps1" <<EOF
\$ErrorActionPreference = 'Continue'
\$bin = "\$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin"
\$env:PATH = ((\$env:PATH -split ';') | Where-Object { \$_ -ne \$bin }) -join ';'
Set-Location "\$env:USERPROFILE\pmart-lan"
& "\$bin\docker.exe" --config "\$env:USERPROFILE\.docker-pmart" compose -f compose.yml --env-file .env $* 2>&1 | ForEach-Object { "\$_" }
"exit=\$LASTEXITCODE"
EOF
  sh deploy/remote/run-ps.sh "$(alias_of "$pc")" "$TMP/$pc.ps1"
}

copy_files() {
  local pc="$1" host; host="$(alias_of "$pc")"
  ssh -o BatchMode=yes "$host" 'powershell -NoProfile -Command "New-Item -ItemType Directory -Force $env:USERPROFILE\pmart-lan | Out-Null"' >/dev/null 2>&1
  (cd "$OUT/$pc" && scp -q -o BatchMode=yes -r $(ls -A | grep -v '^target$') "$host:pmart-lan/") 2>&1 | grep -v -E 'post-quantum|store now|pq.html' || true
}

cmd="${1:-}"; shift || true
case "$cmd" in
  render)
    bash $LAN/init-secrets.sh
    bash $LAN/render.sh ;;
  deploy)
    target="${1:?pc|all}"; shift
    bash $LAN/render.sh >/dev/null
    for pc in $(pcs "$target"); do
      echo "=== $pc deploy $*"
      copy_files "$pc"
      remote_compose "$pc" up -d --remove-orphans "$@"
    done ;;
  status)
    for pc in $(pcs "${1:-all}"); do
      echo "=== $pc"
      remote_compose "$pc" ps -a --format "'table {{.Service}}\t{{.State}}\t{{.Status}}'"
    done ;;
  logs)
    remote_compose "${1:?pc}" logs --no-color --tail "${3:-100}" "${2:?service}" ;;
  restart)
    pc="${1:?pc}"; shift
    remote_compose "$pc" restart "$@" ;;
  down)
    for pc in $(pcs "${1:?pc|all}"); do echo "=== $pc"; remote_compose "$pc" down; done ;;
  *)
    sed -n '2,9p' "$0"; exit 2 ;;
esac
