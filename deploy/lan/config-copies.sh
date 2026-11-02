#!/usr/bin/env bash
# 통합 시험용 설정 사본 관리(ADR-0029). 시험은 config-server 없이 돌아서, 서비스마다
#   src/integrationTest/resources/pmart-config/{application.yml, <앱>.yml}   (api-gateway 는 src/test/resources 에도)
# 에 설정 저장소 정본의 사본을 둔다. 정본은 deploy/lan/.config-repo(GitHub code-and-pick/pmart-config).
#   deploy/lan/config-copies.sh check   사본이 정본과 다르면 목록을 보이고 1 로 끝난다
#   deploy/lan/config-copies.sh sync    정본을 사본으로 복사한다(그 뒤 해당 서비스 시험을 돌린다)
# <앱>-it-env.yml(옮기기 전 환경 자리표시자)은 시험 전용이라 비교·복사하지 않는다.
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO=deploy/lan/.config-repo
mode="${1:-check}"
[ -f "$REPO/application.yml" ] || { echo "설정 저장소 사본 없음: $REPO" >&2; exit 1; }
diffs=0
for dir in services/*/src/*/resources/pmart-config platform-services/*/src/*/resources/pmart-config; do
  [ -d "$dir" ] || continue
  for f in "$dir"/*.yml; do
    b="$(basename "$f")"
    case "$b" in *-it-env.yml) continue ;; esac
    src="$REPO/$b"
    [ -f "$src" ] || { echo "정본 없음: $src (사본 $f)" >&2; diffs=$((diffs + 1)); continue; }
    if ! cmp -s "$src" "$f"; then
      if [ "$mode" = sync ]; then cp "$src" "$f"; echo "synced $f"; else echo "다름: $f"; diffs=$((diffs + 1)); fi
    fi
  done
done
[ "$mode" = check ] && { [ $diffs -eq 0 ] && echo "시험 사본 = 정본" || { echo "$diffs 개 다름 — deploy/lan/config-copies.sh sync" >&2; exit 1; }; }
exit 0
