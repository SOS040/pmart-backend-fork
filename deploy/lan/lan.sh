#!/usr/bin/env bash
# LAN PC 배포 실행기 (PC 1 Git Bash 에서). 배치는 placement.conf, 파일 생성은 render.sh.
#   deploy/lan/lan.sh render                      비밀정보 준비 + PC별 파일 생성(build/)
#   deploy/lan/lan.sh deploy <pc|all> [서비스...]  파일 복사 후 docker compose up -d (서비스 지정 시 그것만)
#   deploy/lan/lan.sh status <pc|all>             컨테이너 상태
#   deploy/lan/lan.sh logs <pc> <서비스> [줄수]     최근 로그(기본 100줄)
#   deploy/lan/lan.sh restart <pc> <서비스...>
#   deploy/lan/lan.sh down <pc|all>               컨테이너 정지·삭제(볼륨은 남긴다)
#   deploy/lan/lan.sh publish-backoffice <dist>    임시 백오피스 빌드(npm run build:lan 결과)를 올리고 backoffice-web 만 다시 배포
#   deploy/lan/lan.sh config-push                 설정 저장소(GitHub pmart-config, lan/)에 render 결과 반영(변경 표시 후 commit·push, ADR-0027)
# deploy 는 설정 저장소가 render 결과와 다르면 멈추고(config-push 먼저), config-server 를 먼저 띄운 뒤 나머지를 올린다.
# 원격 실행: deploy/remote/run-ps.sh (원격 docker 는 %USERPROFILE%\.docker-pmart 설정 — 읽기 전용 pull 토큰)
# 원격 작업 폴더: %USERPROFILE%\pmart-lan. PMart 이외 컨테이너는 건드리지 않는다(compose 프로젝트 pmart-lan 만).
set -euo pipefail
cd "$(dirname "$0")/../.."
LAN=deploy/lan
OUT=$LAN/build
TMP="${TMPDIR:-${TEMP:-/tmp}}/pmart-lan"; mkdir -p "$TMP"

# PC 폴더 = target 파일이 있는 build/<pc> (build/config-repo 등 다른 생성물은 PC 가 아니다)
pcs() { if [ "$1" = all ]; then for d in "$OUT"/*/; do [ -f "$d/target" ] && basename "$d"; done; else [ -f "$OUT/$1/target" ] || { echo "PC 없음: $1 (render 했는지 확인)" >&2; exit 1; }; echo "$1"; fi; }
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

# ---- 설정 저장소(ADR-0027) ----
CFG_CLONE=$LAN/.config-repo  # GitHub pmart-config 로컬 사본(git 제외). PC 1 의 사용자 GitHub 자격 증명으로 push 한다
CFG_URL="${PMART_CONFIG_REPO_URL:-https://github.com/code-and-pick/pmart-config.git}"
config_sync() { # 사본을 원격 최신으로 — 사람이 고친 내용(커밋·미커밋)은 절대 버리지 않는다(ADR-0029: 저장소가 정본)
  # autocrlf 를 끈다: PC 1 Git 은 core.autocrlf=true 라 체크아웃이 CRLF 가 되어 render 결과(LF)와 늘 달라 보인다
  if [ ! -d "$CFG_CLONE/.git" ]; then git clone -q -c core.autocrlf=false "$CFG_URL" "$CFG_CLONE"; fi
  git -C "$CFG_CLONE" config core.autocrlf false
  git -C "$CFG_CLONE" fetch -q origin
  git -C "$CFG_CLONE" rev-parse -q --verify origin/main >/dev/null || return 0 # 빈 원격(첫 push 전)
  if git -C "$CFG_CLONE" rev-parse -q --verify HEAD >/dev/null; then
    # 앞서 있으면 그대로 둔다(올리지 않은 커밋), 뒤처졌으면 fast-forward, 갈라졌으면 멈춘다
    git -C "$CFG_CLONE" merge -q --ff-only origin/main 2>/dev/null \
      || git -C "$CFG_CLONE" merge-base --is-ancestor origin/main HEAD \
      || { echo "설정 저장소 사본($CFG_CLONE)이 원격과 갈라졌다 — 직접 정리한 뒤 다시" >&2; exit 1; }
  else
    git -C "$CFG_CLONE" checkout -q -B main origin/main
  fi
}
config_check() { # 배포 전: render 결과(lan/) = 사본, 사본에 미커밋·미push 가 없다(config-server 는 GitHub 를 읽는다)
  config_sync
  local dirty ahead
  dirty="$(git -C "$CFG_CLONE" status --porcelain)"
  ahead="$(git -C "$CFG_CLONE" rev-list --count origin/main..HEAD 2>/dev/null || echo 1)"
  if [ -n "$dirty" ] || [ "$ahead" != 0 ] \
    || ! diff -r -q "$OUT/config-repo/lan" "$CFG_CLONE/lan" >/dev/null 2>&1; then
    echo "설정 저장소가 GitHub 에 반영되지 않았다(미커밋 ${dirty:+있음}, 미push ${ahead}개, lan/ 차이) — 먼저 deploy/lan/lan.sh config-push" >&2
    diff -r -q "$OUT/config-repo/lan" "$CFG_CLONE/lan" 2>&1 | sed 's/^/  /' >&2 || true
    exit 1
  fi
}
config_server_pc() { grep -l '^  config-server:' "$OUT"/*/compose.yml 2>/dev/null | head -1 | xargs -r dirname | xargs -r basename; }
wait_config_server() {
  local pc="$1" ip; ip="$(awk '{print $2}' "$OUT/$pc/target")"
  for _ in $(seq 1 60); do
    curl -sf -m 3 "http://$ip:8888/actuator/health" | grep -q '"UP"' && { echo "config-server UP ($pc)"; return 0; }
    sleep 3
  done
  echo "config-server 가 UP 이 아니다($pc) — lan.sh logs $pc config-server" >&2; exit 1
}

cmd="${1:-}"; shift || true
case "$cmd" in
  render)
    bash $LAN/init-secrets.sh
    bash $LAN/render.sh ;;
  deploy)
    target="${1:?pc|all}"; shift
    config_sync # 기존 암호문을 재사용하려면 render 전에 사본이 최신이어야 한다
    bash $LAN/render.sh >/dev/null
    config_check
    # config-server 먼저: 다른 앱은 부팅할 때 설정을 받아야 뜬다(fail-fast)
    cpc="$(config_server_pc)"
    if [ -n "$cpc" ] && { [ "$target" = all ] || [ "$target" = "$cpc" ]; } \
      && { [ $# -eq 0 ] || [[ " $* " == *" config-server "* ]]; }; then
      echo "=== $cpc deploy config-server"
      copy_files "$cpc"
      remote_compose "$cpc" up -d config-server
      wait_config_server "$cpc"
    fi
    for pc in $(pcs "$target"); do
      echo "=== $pc deploy $*"
      copy_files "$pc"
      # 기존 볼륨에는 init-db.sql 이 다시 돌지 않으므로, 서비스보다 먼저 새 서비스 DB 를 만든다(없는 것만, \gexec)
      if grep -q '^  postgres:' "$OUT/$pc/compose.yml"; then
        remote_compose "$pc" up -d --wait postgres
        remote_compose "$pc" exec -T postgres psql -U pmart -p 15432 -d pmart -q -f /docker-entrypoint-initdb.d/10-init-db.sql
      fi
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
  publish-backoffice)
    src="${1:?dist 경로(index.html 이 있는 폴더)}"
    [ -f "$src/index.html" ] || { echo "index.html 없음: $src" >&2; exit 1; }
    # 줄 끝 \ 로 이어 쓴 배치: 들여쓰지 않은 줄이 PC, backoffice-web 이 나온 블록의 PC 를 고른다
    pc="$(awk '/^[^#[:space:]]/ {cur = $1} {for (i = 1; i <= NF; i++) if ($i == "backoffice-web") {print cur; exit}}' "$LAN/placement.conf")"
    [ -n "$pc" ] || { echo "placement.conf 에 backoffice-web 없음" >&2; exit 1; }
    rm -rf "$LAN/backoffice-dist" && mkdir -p "$LAN/backoffice-dist" && cp -r "$src/." "$LAN/backoffice-dist/"
    echo "백오피스 빌드 $(find "$LAN/backoffice-dist" -type f | wc -l)개 파일 → $pc"
    "$0" deploy "$pc" backoffice-web ;;
  config-push)
    config_sync
    PMART_RENDER_NO_DIGEST=1 bash $LAN/render.sh >/dev/null # 설정 내용만 필요(이미지 digest 무관)
    rm -rf "$CFG_CLONE/lan" && cp -r "$OUT/config-repo/lan" "$CFG_CLONE/lan"
    git -C "$CFG_CLONE" add -A lan
    if git -C "$CFG_CLONE" diff --cached --quiet; then echo "설정 저장소 변경 없음"; exit 0; fi
    # 값이 아니라 바뀐 키만 보인다(암호문·평문 모두 출력하지 않음)
    git -C "$CFG_CLONE" diff --cached --stat
    git -C "$CFG_CLONE" diff --cached -U0 | grep -E '^[+-][^+-]' | sed -E 's/^([+-][^:]*):.*/\1/' | sort -u | sed 's/^/  /'
    git -C "$CFG_CLONE" -c user.name="$(git config user.name)" -c user.email="$(git config user.email)" \
      commit -q -m "lan: render (pmart-backend $(git rev-parse --short HEAD))"
    git -C "$CFG_CLONE" push -q origin HEAD:main
    echo "pushed $(git -C "$CFG_CLONE" rev-parse --short HEAD) → $CFG_URL" ;;
  down)
    for pc in $(pcs "${1:?pc|all}"); do echo "=== $pc"; remote_compose "$pc" down; done ;;
  *)
    sed -n '2,10p' "$0"; exit 2 ;;
esac
