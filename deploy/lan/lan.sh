#!/usr/bin/env bash
# LAN PC 배포 실행기 (PC 1 Git Bash 에서). 배치는 placement.conf, 파일 생성은 render.sh.
#   deploy/lan/lan.sh render                      비밀정보 준비 + PC별 파일 생성(build/)
#   deploy/lan/lan.sh deploy <pc|all> [서비스...]  파일 복사 후 docker compose up -d (서비스 지정 시 그것만)
#   deploy/lan/lan.sh status <pc|all>             컨테이너 상태
#   deploy/lan/lan.sh logs <pc> <서비스> [줄수]     최근 로그(기본 100줄)
#   deploy/lan/lan.sh restart <pc> <서비스...>
#   deploy/lan/lan.sh down <pc|all>               컨테이너 정지·삭제(볼륨은 남긴다)
#   deploy/lan/lan.sh publish-backoffice <dist>    임시 백오피스 빌드(npm run build:lan 결과)를 올리고 backoffice-web 만 다시 배포
#   deploy/lan/lan.sh publish-hr-backoffice <dist> 인사팀 백오피스 빌드(web/hr-backoffice build:lan 결과)를 올리고 hr-backoffice-web 만 다시 배포(:8081)
#   deploy/lan/lan.sh rag-pull <pc> <모델...>       그 PC 의 ollama 에 모델 받기(ADR-0051, 예: qwen3-embedding:0.6b qwen3:4b)
#   deploy/lan/lan.sh rag-warm <pc> [모델...]       ollama 에 대화 모델을 미리 올림(첫 적재 약 30초 > 호출 시간 제한 25초). ollama 재시작 뒤 한 번.
#                                                   임베딩 모델은 서비스가 CPU 로 올린다(embedding-num-gpu 0) — 여기서 올리면 GPU 에 올라가 대화 모델을 밀어낸다
#   deploy/lan/lan.sh rag-ingest <pc>              운영자 RAG 색인(compose 의 ai-assistant-ingest — rag/knowledge-operator 를 /repo 로, 일회 컨테이너)
#   deploy/lan/lan.sh rag-ingest-customer <pc>     고객 RAG 색인(customer-assistant-ingest — 고객 안내 문서 rag/knowledge-customer, ADR-0051)
#   deploy/lan/lan.sh tls-issue <pc|all>           LAN HTTPS 사설 CA 로 PC 서버 인증서 발급·갱신(1년, docs/architecture/lan-https-private-ca.md)
#   deploy/lan/lan.sh tls-trust <pc|all|pc1>       루트 CA 를 PC 의 Windows 신뢰 저장소에 설치(pc1 = 이 PC, UAC 창)
#   deploy/lan/lan.sh config-push [메시지]        설정 저장소 사본(deploy/lan/.config-repo, 사람이 고친 내용)을 GitHub pmart-config 에 commit·push(ADR-0029)
# deploy 는 설정 저장소 사본에 미커밋·미push 가 있으면 멈추고(config-push 먼저), config-server 를 먼저 띄운 뒤 나머지를 올린다.
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
exit \$LASTEXITCODE
EOF
  sh deploy/remote/run-ps.sh "$(alias_of "$pc")" "$TMP/$pc.ps1" \
    || { echo "원격 docker compose 실패($pc: $*) — 위 출력 확인(접속 실패면 ssh $(alias_of "$pc"))" >&2; return 1; }
}

# 실패하면 멈춘다(2026-11-03: 접속 실패를 삼켜 아무것도 복사·배포하지 않고 0 으로 끝났다)
copy_files() {
  local pc="$1" host out; host="$(alias_of "$pc")"
  # pmart-pc1 = 이 PC(2026-11-15) — SSH 없이 %USERPROFILE%\pmart-lan 에 바로 복사
  if [ "$host" = pmart-pc1 ]; then
    local dst; dst="$(cygpath -u "$USERPROFILE")/pmart-lan"; mkdir -p "$dst"
    (cd "$OUT/$pc" && cp -r $(ls -A | grep -v '^target$') "$dst/") || { echo "$pc 파일 복사 실패 — 배포 중단" >&2; return 1; }
    return 0
  fi
  if ! out="$(ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" 'powershell -NoProfile -Command "New-Item -ItemType Directory -Force $env:USERPROFILE\pmart-lan | Out-Null"' 2>&1)"; then
    printf '%s\n' "$out" | grep -v -E 'post-quantum|store now|pq.html' >&2
    echo "$pc($host) 접속 실패 — 배포 중단" >&2; return 1
  fi
  if ! out="$(cd "$OUT/$pc" && scp -q -o BatchMode=yes -o ConnectTimeout=10 -r $(ls -A | grep -v '^target$') "$host:pmart-lan/" 2>&1)"; then
    printf '%s\n' "$out" | grep -v -E 'post-quantum|store now|pq.html' >&2
    echo "$pc($host) 파일 복사 실패 — 배포 중단(실행 중인 컨테이너가 바인드 파일을 잡고 있는지 확인)" >&2; return 1
  fi
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
config_check() { # 배포 전: 사본에 미커밋·미push 가 없다(config-server 는 GitHub 를 읽는다 — 로컬에만 있는 수정은 반영되지 않는다)
  config_sync
  local dirty ahead
  dirty="$(git -C "$CFG_CLONE" status --porcelain)"
  ahead="$(git -C "$CFG_CLONE" rev-list --count origin/main..HEAD 2>/dev/null || echo 1)"
  if [ -n "$dirty" ] || [ "$ahead" != 0 ]; then
    echo "설정 저장소 사본($CFG_CLONE)이 GitHub 에 반영되지 않았다(미커밋 ${dirty:+있음}, 미push ${ahead}개) — 먼저 deploy/lan/lan.sh config-push" >&2
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
    bash $LAN/config-copies.sh check >/dev/null || { echo "시험용 설정 사본이 정본과 다르다 — deploy/lan/config-copies.sh sync 후 시험·커밋" >&2; exit 1; }
    # Kafka 클러스터 먼저(ADR-0037): config-server(Bus)·Debezium·앱보다 앞. 한 노드만으로는 컨트롤러 과반(2/3)이 안 돼
    # healthy 가 되지 않으므로, 대상 PC 의 노드를 모두 띄운 뒤에 기다린다. 실행 중인 노드는 재생성하지 않는다(--no-recreate).
    # 이어서 한 노드 안에서 토픽 준비(이력 토픽 보관 정책 ADR-0034, 복제 3) — 멱등, 다른 토픽은 건드리지 않음
    knodes=""
    for pc in $(pcs "$target"); do
      for n in $(grep -o '^  kafka-[123]:' "$OUT/$pc/compose.yml" | tr -d ' :'); do
        copy_files "$pc"
        remote_compose "$pc" up -d --no-recreate "$n"
        knodes+="$pc:$n "
      done
    done
    for pn in $knodes; do remote_compose "${pn%%:*}" up -d --no-recreate --wait "${pn#*:}"; done
    if [ -n "$knodes" ]; then
      pn="${knodes%% *}"; kpc="${pn%%:*}"; kn="${pn#*:}"
      remote_compose "$kpc" cp kafka/topics-init.sh "$kn:/tmp/pmart-topics-init.sh"
      remote_compose "$kpc" exec -T "$kn" sh /tmp/pmart-topics-init.sh
    fi
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
      # 서비스를 지정하면 의존 컨테이너는 건드리지 않는다(--no-deps) — 지정 배포가 ollama·rag-pgvector 를 다시 만들어 모델이 내려갔다(2026-11-09)
      if [ $# -gt 0 ]; then remote_compose "$pc" up -d --no-deps "$@"; else remote_compose "$pc" up -d --remove-orphans; fi
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
  publish-hr-backoffice)
    src="${1:?dist 경로(index.html 이 있는 폴더)}"
    [ -f "$src/index.html" ] || { echo "index.html 없음: $src" >&2; exit 1; }
    pc="$(awk '/^[^#[:space:]]/ {cur = $1} {for (i = 1; i <= NF; i++) if ($i == "hr-backoffice-web") {print cur; exit}}' "$LAN/placement.conf")"
    [ -n "$pc" ] || { echo "placement.conf 에 hr-backoffice-web 없음" >&2; exit 1; }
    rm -rf "$LAN/hr-backoffice-dist" && mkdir -p "$LAN/hr-backoffice-dist" && cp -r "$src/." "$LAN/hr-backoffice-dist/"
    echo "인사팀 백오피스 빌드 $(find "$LAN/hr-backoffice-dist" -type f | wc -l)개 파일 → $pc"
    "$0" deploy "$pc" hr-backoffice-web ;;
  config-push)
    # 설정 저장소 사본(사람이 고친 내용)을 commit·push. 메시지는 인자로(없으면 기본). 값은 출력하지 않고 바뀐 키만 보인다
    config_sync
    git -C "$CFG_CLONE" add -A
    if git -C "$CFG_CLONE" diff --cached --quiet; then
      [ "$(git -C "$CFG_CLONE" rev-list --count origin/main..HEAD)" = 0 ] && { echo "설정 저장소 변경 없음"; exit 0; }
    else
      git -C "$CFG_CLONE" diff --cached --stat
      git -C "$CFG_CLONE" diff --cached -U0 | grep -E '^[+-][^+-]' | sed -E 's/^([+-][^:]*):.*/\1/' | sort -u | sed 's/^/  /'
      git -C "$CFG_CLONE" -c user.name="$(git config user.name)" -c user.email="$(git config user.email)" \
        commit -q -m "${1:-설정 변경 (pmart-backend $(git rev-parse --short HEAD))}"
    fi
    git -C "$CFG_CLONE" push -q origin HEAD:main
    echo "pushed $(git -C "$CFG_CLONE" rev-parse --short HEAD) → $CFG_URL" ;;
  rag-pull)
    pc="${1:?pc}"; shift
    for m in "$@"; do remote_compose "$pc" exec -T ollama ollama pull "$m" | tail -1; done
    remote_compose "$pc" exec -T ollama ollama list ;;
  rag-warm)
    pc="${1:?pc}"; shift
    models="${*:-qwen3:4b}"
    for m in $models; do
      echo "warm $m"
      # 표준입력을 닫는다 — 터미널이 아니면 ollama run 이 표준입력 끝을 기다려 원격 실행에서 멈췄다(2026-11-09)
      remote_compose "$pc" exec -T ollama sh -c "'ollama run $m --keepalive 24h ok </dev/null'" >/dev/null 2>&1 || echo "  실패: $m" >&2
    done
    remote_compose "$pc" exec -T ollama ollama ps ;;
  rag-ingest)
    "$0" rag-warm "${1:?pc}"
    # 같은 이미지·설정(lan)으로 한 번 돌고 끝난다: 웹·Eureka·Bus 끔, 문서는 읽기 전용. 색인 중에도 기존 ACTIVE 로 답한다(ADR-0036 결정 6)
    pc="${1:?pc}"
    copy_files "$pc"
    remote_compose "$pc" run --rm --no-deps ai-assistant-ingest ;;
  rag-ingest-customer)
    "$0" rag-warm "${1:?pc}"
    pc="${1:?pc}"
    copy_files "$pc"
    remote_compose "$pc" run --rm --no-deps customer-assistant-ingest ;;
  down)
    for pc in $(pcs "${1:?pc|all}"); do echo "=== $pc"; remote_compose "$pc" down; done ;;
  tls-issue)
    # LAN 개발용 HTTPS(docs/architecture/lan-https-private-ca.md): PC 서버 인증서 발급·갱신(SAN = LAN IP + 컴퓨터 이름 + tls/extra-sans.conf)
    source "$LAN/tls/tls.sh"; source "$LAN/ipcheck.sh"
    for pc in $(pcs "${1:?pc|all}"); do
      read -r _ ip alias < "$OUT/$pc/target"
      tls_issue "$pc" "$ip" "$(ipcheck_hostname "$alias")"
    done ;;
  tls-trust)
    # 루트 CA 를 PC 의 Windows 신뢰 저장소(LocalMachine\Root)에 설치. pc1 = 이 PC(UAC 창이 뜬다)
    source "$LAN/tls/tls.sh"; tls_ensure_ca
    targets="${1:?pc|all|pc1}"
    if [ "$targets" = pc1 ] || [ "$targets" = all ]; then
      echo "=== pc1 (이 PC — 관리자 승인 창)"
      res="$TMP/tls-trust-pc1.txt"; rm -f "$res"
      # BOM 을 붙인다 — Windows PowerShell 5.1 은 BOM 없는 UTF-8 스크립트를 ANSI 로 읽어 한글이 깨진다
      { printf '\xEF\xBB\xBF'; tls_trust_ps "'$(cygpath -w "$(pwd)/$TLS_CA_CRT")'" "$(cygpath -w "$res")"; } > "$TMP/tls-trust-pc1.ps1"
      powershell -NoProfile -Command "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','\"$(cygpath -w "$TMP/tls-trust-pc1.ps1")\"'"
      cat "$res" 2>/dev/null || echo "pc1 결과 없음(승인 창을 거절했는지 확인)"
    fi
    if [ "$targets" != pc1 ]; then
      for pc in $(pcs "$targets"); do
        echo "=== $pc"; a="$(alias_of "$pc")"
        scp -q -o BatchMode=yes -o ConnectTimeout=10 "$TLS_CA_CRT" "$a:pmart-lan-root-ca.crt" || { echo "$pc 복사 실패" >&2; continue; }
        tls_trust_ps "(Join-Path \$env:USERPROFILE 'pmart-lan-root-ca.crt')" > "$TMP/tls-trust-$pc.ps1"
        sh deploy/remote/run-ps.sh "$a" "$TMP/tls-trust-$pc.ps1"
      done
    fi ;;
  *)
    sed -n '2,10p' "$0"; exit 2 ;;
esac
