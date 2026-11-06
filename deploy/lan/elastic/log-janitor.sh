#!/bin/sh
# 로그 색인 크기 상한(ADR-0030·0031, 사용자 결정 2026-11-02): ILM 은 기간(3일)으로만 지우므로 크기는 여기서 5분마다 본다.
# 1) 색인 하나가 1GB 를 넘으면 지운다(같은 날 새 색인이 다시 생긴다 — 그날 앞부분 로그는 잃는다)
# 2) pmart-logs-* 전체가 4GB 를 넘으면 오래된 것부터 지운다
# 3) 추적 색인 zipkin-span-*: 3일 보존(색인 이름의 날짜) + 전체 2GB 상한
# 상품 검색 색인(products·sku-prices)은 건드리지 않는다.
set -u
ES="${PMART_ES_URL:-http://elasticsearch:9200}"
MAX_INDEX=$((1024 * 1024 * 1024))
MAX_TOTAL=$((4 * 1024 * 1024 * 1024))
MAX_ZIPKIN=$((2 * 1024 * 1024 * 1024))
ZIPKIN_DAYS=3
INTERVAL="${PMART_JANITOR_INTERVAL:-300}"

while true; do
  # 이름 크기(바이트) — 만든 순서(오래된 것 먼저)
  list="$(curl -s "$ES/_cat/indices/pmart-logs-*?h=index,store.size&bytes=b&s=creation.date" 2>/dev/null || true)"
  if [ -n "$list" ] && ! printf '%s' "$list" | grep -q '"error"'; then
    printf '%s\n' "$list" | while read -r idx size; do
      [ -n "${idx:-}" ] || continue
      if [ "${size:-0}" -gt "$MAX_INDEX" ]; then
        echo "$(date -u +%FT%TZ) $idx ${size}B > 1GB → 삭제"
        curl -s -o /dev/null -X DELETE "$ES/$idx"
      fi
    done
    total=0
    list="$(curl -s "$ES/_cat/indices/pmart-logs-*?h=index,store.size&bytes=b&s=creation.date" 2>/dev/null || true)"
    total="$(printf '%s\n' "$list" | awk '{s += $2} END {print s + 0}')"
    for idx in $(printf '%s\n' "$list" | awk '{print $1}'); do
      [ "$total" -gt "$MAX_TOTAL" ] || break
      size="$(printf '%s\n' "$list" | awk -v i="$idx" '$1 == i {print $2}')"
      echo "$(date -u +%FT%TZ) 전체 ${total}B > 4GB → $idx(${size}B) 삭제"
      curl -s -o /dev/null -X DELETE "$ES/$idx"
      total=$((total - size))
    done
  fi

  # 추적(Zipkin, 2026-11-06): 색인 zipkin-span-YYYY-MM-DD. 로그와 같은 3일 보존(이름의 날짜로) + 전체 2GB 상한(오래된 것부터)
  zlist="$(curl -s "$ES/_cat/indices/zipkin-span-*?h=index,store.size&bytes=b&s=index" 2>/dev/null || true)"
  if [ -n "$zlist" ] && ! printf '%s' "$zlist" | grep -q '"error"'; then
    keep_from="$(date -u -d "@$(( $(date +%s) - (ZIPKIN_DAYS - 1) * 86400 ))" +%F)"
    for idx in $(printf '%s\n' "$zlist" | awk '{print $1}'); do
      day="${idx#zipkin-span-}"
      if [ "$day" \< "$keep_from" ]; then
        echo "$(date -u +%FT%TZ) $idx 보존 ${ZIPKIN_DAYS}일 지남 → 삭제"
        curl -s -o /dev/null -X DELETE "$ES/$idx"
      fi
    done
    zlist="$(curl -s "$ES/_cat/indices/zipkin-span-*?h=index,store.size&bytes=b&s=index" 2>/dev/null || true)"
    ztotal="$(printf '%s\n' "$zlist" | awk '{s += $2} END {print s + 0}')"
    for idx in $(printf '%s\n' "$zlist" | awk '{print $1}'); do
      [ "$ztotal" -gt "$MAX_ZIPKIN" ] || break
      size="$(printf '%s\n' "$zlist" | awk -v i="$idx" '$1 == i {print $2}')"
      echo "$(date -u +%FT%TZ) 추적 전체 ${ztotal}B > 2GB → $idx(${size}B) 삭제"
      curl -s -o /dev/null -X DELETE "$ES/$idx"
      ztotal=$((ztotal - size))
    done
  fi
  sleep "$INTERVAL"
done
