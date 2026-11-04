#!/bin/sh
# Elasticsearch 준비(ADR-0031). elasticsearch 와 같은 PC 에서 한 번 돌고 끝난다(다시 돌려도 같은 결과). Logstash 는 이것이 끝난 뒤 뜬다.
# 1) 상품 검색 색인(products-v3·sku-prices-v1 + 별칭이 없을 때만) — 정의는 search-service 의 elasticsearch/*.json(render 가 복사). 없을 때만 만든다
# 2) 로그 보존: ILM pmart-logs(3일 뒤 삭제) + 색인 틀 pmart-logs-*(복제본 0, 잘못된 형식 필드는 버리고 문서는 남김)
#    크기 상한(색인 1GB·전체 4GB)은 ILM 이 못 하므로 log-janitor 가 한다. OpenSearch 때와 같이 ES 의 디스크 경고는 1TB 가상 디스크
#    기준이라 실제 C: 보호가 아니다(ADR-0030) — 이 상한들이 보호 장치다
# 3) Kibana 데이터 뷰 pmart-logs-* (시간 필드 @timestamp)
set -eu
ES="${PMART_ES_URL:-http://elasticsearch:9200}"
KB="${PMART_KIBANA_URL:-http://kibana:5601}"
KB_WAIT="${PMART_KIBANA_WAIT:-100}" # 3초 단위

until curl -s -o /dev/null "$ES"; do sleep 3; done

for f in /init/indices/*.json; do
  idx="$(basename "$f" .json)"
  code="$(curl -s -o /dev/null -w '%{http_code}' -I "$ES/$idx")"
  if [ "$code" = 404 ]; then
    curl -s -o /dev/null -w "index $idx %{http_code}\n" -X PUT "$ES/$idx" -H 'Content-Type: application/json' --data-binary @"$f"
  else
    echo "index $idx exists"
  fi
  # 별칭(이름에서 -vN 을 뗀 것)이 아직 없을 때만 붙인다. 이전 버전을 가리키고 있으면 두고, 전환은 재색인 절차(REINDEX.md)가 한다
  alias="${idx%-v[0-9]*}"
  if [ "$(curl -s -o /dev/null -w '%{http_code}' -I "$ES/_alias/$alias")" = 404 ]; then
    curl -s -o /dev/null -w "alias $alias -> $idx %{http_code}\n" -X POST "$ES/_aliases" -H 'Content-Type: application/json' \
      -d "{\"actions\":[{\"add\":{\"index\":\"$idx\",\"alias\":\"$alias\"}}]}"
  else
    echo "alias $alias exists"
  fi
done

curl -s -o /dev/null -w "ilm policy %{http_code}\n" -X PUT "$ES/_ilm/policy/pmart-logs" \
  -H 'Content-Type: application/json' -d '{
  "policy": { "phases": {
    "hot": { "actions": {} },
    "delete": { "min_age": "3d", "actions": { "delete": {} } }
  } }
}'

curl -s -o /dev/null -w "index template %{http_code}\n" -X PUT "$ES/_index_template/pmart-logs" \
  -H 'Content-Type: application/json' -d '{
  "index_patterns": ["pmart-logs-*"],
  "template": {
    "settings": { "number_of_shards": 1, "number_of_replicas": 0, "index.lifecycle.name": "pmart-logs",
                  "index.mapping.ignore_malformed": true, "index.mapping.total_fields.limit": 2000 },
    "mappings": {
      "dynamic_templates": [ { "strings": { "match_mapping_type": "string", "mapping": { "type": "keyword", "ignore_above": 1024 } } } ],
      "properties": {
        "@timestamp": { "type": "date" },
        "message": { "type": "text" },
        "stack_trace": { "type": "text" }
      }
    }
  }
}'

# Kibana 는 늦게 뜬다 — 200 이 될 때까지(기동 중에는 503). 화면 설정이라 실패해도 멈추지 않는다
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$KB/api/status")" = 200 ] || [ $i -ge "$KB_WAIT" ]; do i=$((i + 1)); sleep 3; done
curl -s -o /dev/null -w "kibana data view %{http_code}\n" -X POST "$KB/api/data_views/data_view" \
  -H 'Content-Type: application/json' -H 'kbn-xsrf: true' -d '{
  "data_view": { "id": "pmart-logs", "name": "pmart-logs", "title": "pmart-logs-*", "timeFieldName": "@timestamp" },
  "override": true
}' || true
curl -s -o /dev/null -w "kibana default view %{http_code}\n" -X POST "$KB/api/data_views/default" \
  -H 'Content-Type: application/json' -H 'kbn-xsrf: true' -d '{ "data_view_id": "pmart-logs", "force": true }' || true
echo "es-init done"
