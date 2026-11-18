#!/usr/bin/env bash
# LAN 웹 맞추기(2026-11-18, 사용자 결정 "(가)·(다)") — pmart-clients main 과 LAN 에 올라간 웹 앱 4개를 비교한다.
#   deploy/lan/lan.sh web-sync            뒤처진 앱만 보여 준다(배포 안 함). 종료 코드 0 = 모두 맞음, 3 = 뒤처짐, 1 = 확인 못 함
#   deploy/lan/lan.sh web-sync --apply    뒤처진 앱만 빌드해 publish-<앱> 으로 올리고, 올라간 version.txt 를 확인한다(배포 잠금 안에서)
#
# 왜: pmart-clients PR 은 다른 쪽(bae-easy)이 병합하고 LAN 웹 빌드·배포는 PC 1 만 하는데, 병합 뒤 PC 1 에 알리는 단계가 없어
#   2026-11-17 오후 PR #64~#102 가 실서버에 안 들어간 채 남았다. 무엇이 올라가 있는지도 알 수 없었다.
# - 올라간 버전: 빌드마다 dist/version.txt(첫 줄 = pmart-clients 커밋 전체 SHA)를 같이 올린다 → https://<PC>:<포트>/version.txt.
#   손으로 올린 빌드(version.txt 없음 — Nginx 는 index.html 로 대신 답한다)는 "버전 모름" = 뒤처짐으로 본다.
# - 뒤처짐: 올라간 SHA..main 사이에 web/<앱>·web/core·web/ui·package.json·package-lock.json 이 바뀌었으면.
# - 빌드는 개발 폴더가 아닌 전용 worktree(LAN_WEB_BUILD, 기본 ../pmart-clients-lan — main 에서 detached)에서 한다.
#   개발 폴더의 목업 개발 서버(5173)가 파일을 잡아 npm ci 가 깨지거나(2026-11-18), 고치던 파일이 빌드에 섞이지 않게.
# 누가: PR 세션이 PR 확인 때마다 돌린다((가)). 30분마다 확인만 하고 뒤처지면 알린다((다)).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
LAN=deploy/lan
CLIENTS="${PMART_CLIENTS_DIR:-../pmart-clients}"
BUILD="${LAN_WEB_BUILD:-../pmart-clients-lan}"
APPLY=0
[ "${1:-}" = --apply ] && APPLY=1

# 앱 → 포트(https). 앱 이름 = web/<앱> = lan.sh publish-<앱> = 컨테이너 <앱>-web
APPS="backoffice:443 hr-backoffice:8444 mfc-backoffice:8445 rider-backoffice:8446"

host_of() { # placement.conf 에서 <앱>-web 이 놓인 PC 의 IP
  awk -v svc="$1-web" '/^[^#[:space:]]/ {cur = $2} {for (i = 1; i <= NF; i++) if ($i == svc) {print cur; exit}}' "$LAN/placement.conf"
}

deployed_sha() { # 올라간 version.txt 의 SHA(없거나 SHA 모양이 아니면 빈 값)
  local url="https://$(host_of "$1"):$2/version.txt" body
  body="$(curl -sk --max-time 10 "$url" 2>/dev/null | head -1 | tr -d '\r' || true)"
  [[ "$body" =~ ^[0-9a-f]{40}$ ]] && echo "$body" || true
}

reachable() { curl -sk --max-time 10 -o /dev/null -w '%{http_code}' "https://$(host_of "$1"):$2/" 2>/dev/null | grep -q '^200$'; }

git -C "$CLIENTS" fetch -q origin
target="$(git -C "$CLIENTS" rev-parse origin/main)"
echo "pmart-clients main = ${target:0:8} ($(git -C "$CLIENTS" log -1 --format='%cd %s' --date=format:'%m-%d %H:%M' origin/main | cut -c1-80))"

behind=()
unknown=0
for entry in $APPS; do
  app="${entry%%:*}"; port="${entry##*:}"
  if ! reachable "$app" "$port"; then
    echo "  ??   $app — https://$(host_of "$app"):$port 응답 없음"; unknown=1; continue
  fi
  have="$(deployed_sha "$app" "$port")"
  if [ -z "$have" ]; then
    echo "  뒤처짐 $app — 올라간 버전 모름(version.txt 없음 — 손으로 올린 빌드)"; behind+=("$app"); continue
  fi
  if ! git -C "$CLIENTS" cat-file -e "$have^{commit}" 2>/dev/null; then
    echo "  뒤처짐 $app — 올라간 ${have:0:8} 이 저장소에 없음"; behind+=("$app"); continue
  fi
  changed="$(git -C "$CLIENTS" diff --name-only "$have" "$target" -- "web/$app" web/core web/ui package.json package-lock.json | wc -l)"
  if [ "$changed" -gt 0 ]; then
    n="$(git -C "$CLIENTS" rev-list --count "$have..$target" -- "web/$app" web/core web/ui package.json package-lock.json)"
    echo "  뒤처짐 $app — 올라간 ${have:0:8}, 그 뒤 관련 커밋 ${n}개·파일 ${changed}개"; behind+=("$app")
  else
    echo "  맞음   $app — ${have:0:8}"
  fi
done

if [ "$APPLY" = 0 ]; then
  [ "${#behind[@]}" -gt 0 ] && { echo "뒤처진 앱 ${#behind[@]}개 — 배포: PMART_LAN_WHO=<세션> deploy/lan/lan.sh web-sync --apply"; exit 3; }
  [ "$unknown" = 1 ] && exit 1
  exit 0
fi
[ "${#behind[@]}" -eq 0 ] && { echo "올릴 것 없음"; exit 0; }

# 전용 worktree 를 main 으로(이 폴더는 빌드 전용 — 손으로 고치지 않는다)
if [ ! -e "$BUILD/.git" ]; then
  git -C "$CLIENTS" worktree add -q --detach "$BUILD" "$target"
else
  git -C "$BUILD" checkout -q -f --detach "$target"
  git -C "$BUILD" clean -fdq
fi
lock_sha="$(git -C "$BUILD" rev-parse "$target:package-lock.json")"
if [ "$(cat "$BUILD/node_modules/.web-sync-lock" 2>/dev/null || true)" != "$lock_sha" ]; then
  echo "npm ci ($BUILD — package-lock ${lock_sha:0:8})"
  (cd "$BUILD" && npm ci --no-audit --no-fund --loglevel=error)
  echo "$lock_sha" > "$BUILD/node_modules/.web-sync-lock"
fi
# 계약 타입(src/api/generated — git 에 없음)은 tools/web-tools(workspace 밖, 따로 설치)의 gen:api 가 형제 폴더
# ../pmart-backend/contracts/openapi 에서 만든다(개발 폴더 빌드와 같은 길). backoffice 의 gen:api 가 core·hr·mfc 사본도 만들고,
# rider-backoffice 는 자기 gen:api(사본 복사 포함)를 따로 돈다.
tools_sha="$(git -C "$BUILD" rev-parse "$target:tools/web-tools/package-lock.json")"
if [ "$(cat "$BUILD/tools/web-tools/node_modules/.web-sync-lock" 2>/dev/null || true)" != "$tools_sha" ]; then
  echo "npm ci (tools/web-tools — ${tools_sha:0:8})"
  (cd "$BUILD/tools/web-tools" && npm ci --no-audit --no-fund --loglevel=error)
  echo "$tools_sha" > "$BUILD/tools/web-tools/node_modules/.web-sync-lock"
fi
echo "계약 타입 생성(pmart-backend $(git rev-parse --short HEAD) contracts)"
gen_log="${TMPDIR:-${TEMP:-/tmp}}/pmart-web-sync-gen.log"
gen() { (cd "$BUILD" && npm run gen:api -w "web/$1") > "$gen_log" 2>&1 || { tail -20 "$gen_log" >&2; echo "계약 타입 생성 실패: $1" >&2; exit 1; }; }
gen backoffice
case " ${behind[*]} " in *" rider-backoffice "*) gen rider-backoffice ;; esac

# 카카오 지도 JavaScript 키(2026-11-18 사용자 "기존 웹빌드의 자바스크립트 키랑 같아" — 라이더 백오피스 관제 지도). 빌드 환경에만 넘기고 출력하지 않는다.
# 파일이 없으면 빈 값 — 앱은 "카카오 지도 키가 없어 표로만" 보인다. 키를 쓰는 도메인은 카카오 콘솔 Web 플랫폼에 등록돼 있어야 SDK 가 받는다.
KAKAO_JS=""
[ -f "$LAN/.secrets/kakao-js-key" ] && KAKAO_JS="$(tr -d '
' < "$LAN/.secrets/kakao-js-key")"

failed=0
for app in "${behind[@]}"; do
  port=""
  for e in $APPS; do if [ "${e%%:*}" = "$app" ]; then port="${e##*:}"; fi; done
  echo "=== $app 빌드 (${target:0:8})"
  if ! (cd "$BUILD" && VITE_KAKAO_JS_KEY="$KAKAO_JS" npm run build:lan -w "web/$app" --silent); then
    echo "  빌드 실패: $app — 올리지 않음" >&2; failed=1; continue
  fi
  printf '%s\n%s\n' "$target" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$BUILD/web/$app/dist/version.txt"
  bash "$LAN/lan.sh" "publish-$app" "$BUILD/web/$app/dist" | tail -2
  have="$(deployed_sha "$app" "$port")"
  if [ "$have" = "$target" ]; then
    echo "  확인: $app version.txt = ${target:0:8}"
  else
    echo "  확인 실패: $app version.txt = '${have:-없음}'" >&2; failed=1
  fi
done
exit "$failed"
