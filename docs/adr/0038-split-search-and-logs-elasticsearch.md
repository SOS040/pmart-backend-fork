# ADR-0038 검색용 Elasticsearch 와 로그용 Elasticsearch 를 나눈다 — 검색 ES·Logstash 는 pc3, 로그 ES·Logstash·Kibana 는 pc4

- 상태: 제안 (2026-11-05, 사용자 결정 — Kafka 클러스터 전환과 같은 날 2026-11-06 적용 예정)
- 대체: ADR-0031 결정 5("검색과 로그는 같은 Elasticsearch 노드 — 로그가 검색에 영향을 주면 그때 나눈다"). ADR-0031 의 나머지 결정(ES 8.18 + nori·n-gram, 색인 주체 Logstash, Filebeat → Logstash → ES → Kibana, 상한 유지)은 그대로다.
- 관련: ADR-0030(관측·디스크 상한), ADR-0034(재색인 = Kafka 전체 재생), ADR-0037(Kafka 클러스터), `docs/operations/2026-11-06-kafka-cluster-elk-split-plan.md`

## 배경
- 지금 pc4 의 ES 하나가 검색 색인(`products`·`sku-prices`·`sku-handlings` 별칭)과 앱 로그(`pmart-logs-*`)를 함께 담고, Logstash 하나가 두 파이프라인(`search-index`·`logs`)을 돈다.
- 로그 양은 앱 수·로그 수준에 따라 갑자기 늘고(2026-10-25 pc3 디스크 고갈), 그때 힙·디스크·Logstash 작업자를 검색과 나눠 쓴다. 디스크 워터마크에 걸리면 ES 가 색인을 읽기 전용으로 바꾸는데, 그 대상에 검색 색인도 들어간다.
- 검색은 고객 화면 경로(storefront → search-service)이고 로그는 운영 도구다. 장애·재시작·용량 정리 주기가 다르다.
- Kafka 3노드 전환(ADR-0037)으로 어차피 전체를 다시 올리고 검색 색인을 재생성(ADR-0034)하므로 지금 나누는 비용이 가장 작다.

## 결정
1. **배치**:
   - pc3: `es-search`(검색 색인 전용), `logstash-search`(파이프라인 `search-index`, Kafka 이력 토픽 → es-search), `es-init-search`(검색 색인·별칭), `es-exporter-search`.
   - pc4: `es-logs`(로그 전용), `logstash-logs`(파이프라인 `logs`, Beats 5044 → es-logs), `es-init-logs`(색인 틀·ILM·Kibana 데이터 뷰), `es-exporter-logs`, `log-janitor`, Kibana(→ es-logs).
   - 둘 다 단일 노드·보안 끔(ADR-0031 결정 1과 같음). 이미지 버전은 ADR-0031 결정 7 그대로.
2. **색인 자동 생성 범위를 각각 좁힌다**(`action.auto_create_index`):
   - es-search: 시스템 색인만. 검색 색인은 es-init 이 매핑과 함께 만든다.
   - es-logs: `pmart-logs-*` 와 시스템 색인.
   - 이렇게 하면 로그가 실수로 검색 ES 에, 검색 문서가 로그 ES 에 들어가지 않는다.
3. **접속 주소**:
   - search-service → `es-search:9200`(설정 저장소 `search-service*.yml` + 시험 사본).
   - Filebeat(PC 마다) → `logstash-logs:5044`.
   - Grafana 로그 데이터 소스 → `es-logs:9200`, 검색 대시보드 ES 패널 → `es-exporter-search`.
   - `reindex.sh` → es-search·logstash-search.
4. **자원**: 각 ES·Logstash 메모리 상한은 지금 값(각 1.5 GiB)을 그대로 쓴다. 로그 보관 3일·디스크 상한(ADR-0030)은 es-logs 에만 적용된다.
5. **감시**: ES 경보(Unreachable·ClusterRed·HeapHigh)를 `es-search`·`es-logs` 별로 둔다(어느 쪽인지 이름·레이블로 구분).
6. **전환**: 검색 색인은 새 es-search 에 Kafka 이력 전체 재생으로 다시 만든다(logstash-search 새 소비 그룹). 기존 로그는 옮기지 않는다. 옛 ES 볼륨은 롤백용으로 남긴다.

## 대안
- **같은 ES 유지(ADR-0031 결정 5)**: 컨테이너·메모리가 적다. 로그 폭주·디스크 워터마크·힙 압박이 검색으로 번지는 문제가 남는다.
- **같은 ES 에 노드 2개(검색·로그 노드 역할 분리, 샤드 할당 필터)**: 한 클러스터라 Kibana·관리 화면이 하나로 남지만, 클러스터 상태(마스터·디스크 워터마크 설정)는 공유하고 설정이 복잡하다.
- **Logstash 하나 유지(두 ES 로 출력)**: 컨테이너가 하나 줄지만, 로그 파이프라인이 밀리면 같은 JVM 의 검색 파이프라인도 영향을 받는다. ES 와 같은 PC 에 두는 편이 네트워크 경로도 짧다.

## 결과
- pc3 에 ES·Logstash 메모리(약 3 GiB)가 새로 들고 pc4 는 검색 파이프라인 몫만큼 준다. pc3 사용량 7.9 / 23.5 GiB 로 수용 가능하다.
- 로그 ES 를 지우거나 재시작해도 검색은 영향을 받지 않는다. 반대로 검색 재색인이 로그 수집을 막지 않는다.
- Kibana 로는 로그만 본다. 검색 색인은 es-search 의 API(또는 Grafana 지표)로 본다. 필요하면 Kibana 를 하나 더 두지 않고 `curl` 로 확인한다.
- 구성요소 이름이 바뀌므로(`elasticsearch`·`logstash` → 각 두 개) 운영 문서·스크립트·대시보드의 이름을 함께 고친다.
