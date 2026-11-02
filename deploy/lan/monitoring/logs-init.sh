#!/bin/sh
# OpenSearch 로그 색인 준비(ADR-0030). opensearch 와 같은 PC 에서 한 번 돌고 끝난다(다시 돌려도 같은 결과).
# 1) 보존 정책(ISM): pmart-logs-* 는 3일이 지나거나 1GB 를 넘으면 삭제(사용자 결정 2026-11-02). 색인은 날짜마다 하나라
#    동시에 있는 색인은 최대 4개(오늘~3일 전) → 로그 전체 약 4GB 이하. 같은 날 1GB 를 넘겨 지워지면 그날 색인이 새로 생긴다.
#    OpenSearch 의 디스크 경고(95%)는 Docker 가상 디스크(1TB) 기준이라 실제 C: 가 먼저 찬다 — 이 상한이 실제 보호 장치다(ADR-0030).
#    이미 있으면 seq_no·primary_term 으로 갱신한다
# 2) 색인 틀: 잘못된 형식 필드는 버리고 문서는 남긴다
# 3) 단일 노드: Query Insights 의 기록 색인(top_queries-날짜, 복제본 1 고정 → 복제본을 둘 노드가 없어 UNASSIGNED·클러스터 yellow)을
#    만들지 않게 exporter 를 none 으로(상위 쿼리는 메모리에서 GET /_insights/top_queries 로 본다). 남은 것은 복제본 0.
#    ISM·작업 스케줄러 시스템 색인(.opendistro-*)도 복제본 1 로 생겨 같은 문제 → 이력은 클러스터 설정, 나머지는 바로 복제본 0 (2026-11-02 LAN)
# 4) OpenSearch Dashboards 색인 패턴 pmart-logs-* (시간 필드 @timestamp)
set -eu
OS="${PMART_OS_URL:-http://opensearch:9200}"
OSD="${PMART_OSD_URL:-http://opensearch-dashboards:5601}"
OSD_WAIT="${PMART_OSD_WAIT:-100}" # 3초 단위

until curl -s -o /dev/null "$OS"; do sleep 3; done

POLICY="$OS/_plugins/_ism/policies/pmart-logs-retention"
cur="$(curl -s "$POLICY" || true)"
seq="$(printf '%s' "$cur" | sed -n 's/.*"_seq_no":\([0-9]*\).*/\1/p')"
term="$(printf '%s' "$cur" | sed -n 's/.*"_primary_term":\([0-9]*\).*/\1/p')"
q=""
if [ -n "$seq" ] && [ -n "$term" ]; then q="?if_seq_no=$seq&if_primary_term=$term"; fi
curl -s -o /dev/null -w "ism policy %{http_code}\n" -X PUT "$POLICY$q" \
  -H 'Content-Type: application/json' -d '{
  "policy": {
    "description": "pmart-logs-* 3일 또는 1GB (ADR-0030)",
    "default_state": "hot",
    "states": [
      { "name": "hot", "actions": [], "transitions": [
          { "state_name": "delete", "conditions": { "min_index_age": "3d" } },
          { "state_name": "delete", "conditions": { "min_size": "1gb" } } ] },
      { "name": "delete", "actions": [ { "delete": {} } ], "transitions": [] }
    ],
    "ism_template": [ { "index_patterns": ["pmart-logs-*"], "priority": 100 } ]
  }
}'

curl -s -o /dev/null -w "index template %{http_code}\n" -X PUT "$OS/_index_template/pmart-logs" \
  -H 'Content-Type: application/json' -d '{
  "index_patterns": ["pmart-logs-*"],
  "template": {
    "settings": { "number_of_shards": 1, "number_of_replicas": 0, "index.mapping.ignore_malformed": true, "index.mapping.total_fields.limit": 2000 },
    "mappings": {
      "dynamic_templates": [ { "strings": { "match_mapping_type": "string", "mapping": { "type": "keyword", "ignore_above": 1024 } } } ],
      "properties": {
        "@timestamp": { "type": "date" },
        "msg": { "type": "text" },
        "log": { "type": "text" },
        "stack_trace": { "type": "text" }
      }
    }
  }
}'

curl -s -o /dev/null -w "query insights exporter %{http_code}\n" -X PUT "$OS/_cluster/settings" \
  -H 'Content-Type: application/json' -d '{ "persistent": { "search.insights.top_queries.exporter.type": "none" } }'
curl -s -o /dev/null -w "top_queries replicas %{http_code}\n" -X PUT "$OS/top_queries-*/_settings?allow_no_indices=true" \
  -H 'Content-Type: application/json' -d '{ "index": { "number_of_replicas": 0 } }'
# ISM·작업 스케줄러가 만드는 시스템 색인(.opendistro-ism-config, -ism-managed-index-history-날짜, -job-scheduler-lock)도 복제본 1 로
# 생긴다(2026-11-02 LAN). 이력 색인은 날마다 새로 생기므로 클러스터 설정으로 복제본 0, 이미 있는 .opendistro-* 는 바로 0
curl -s -o /dev/null -w "ism history replicas setting %{http_code}\n" -X PUT "$OS/_cluster/settings" \
  -H 'Content-Type: application/json' -d '{ "persistent": { "plugins.index_state_management.history.number_of_replicas": 0 } }'
curl -s -o /dev/null -w "system index replicas %{http_code}\n" -X PUT "$OS/.opendistro-*/_settings?allow_no_indices=true&expand_wildcards=all" \
  -H 'Content-Type: application/json' -d '{ "index": { "number_of_replicas": 0 } }'

# Dashboards 는 늦게 뜬다 — 최대 5분 기다린다. 화면 설정이라 실패해도 앞의 보존 정책은 이미 들어갔다(멈추지 않는다)
i=0
# 기동 중에는 503 을 준다 — 200 이 될 때까지
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$OSD/api/status")" = 200 ] || [ $i -ge "$OSD_WAIT" ]; do i=$((i + 1)); sleep 3; done
curl -s -o /dev/null -w "index pattern %{http_code}\n" -X POST "$OSD/api/saved_objects/index-pattern/pmart-logs?overwrite=true" \
  -H 'Content-Type: application/json' -H 'osd-xsrf: true' -d '{
  "attributes": { "title": "pmart-logs-*", "timeFieldName": "@timestamp" }
}' || true
curl -s -o /dev/null -w "default index %{http_code}\n" -X POST "$OSD/api/opensearch-dashboards/settings" \
  -H 'Content-Type: application/json' -H 'osd-xsrf: true' -d '{ "changes": { "defaultIndex": "pmart-logs" } }' || true
echo "logs-init done"
