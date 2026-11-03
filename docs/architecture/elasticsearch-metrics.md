# Elasticsearch 지표 수집 (LAN)

작성 2026-11-03 (커밋 239b54f). **LAN 미배포** — 아래 지표 이름은 설정과 elasticsearch-exporter 문서 기준이며, 실제 수집은 배포 후 확인한다.
구성 정본: `deploy/lan/render.sh`(es-exporter 컴포넌트·Prometheus job), `deploy/lan/monitoring/alert-rules.yml`.

## 1. 수집 경로

```
Elasticsearch (pc4, :9200)
   ▲ 조회 (exporter 가 ES REST API 호출, 시간 제한 5s)
es-exporter (pc4, :9114, quay.io/prometheuscommunity/elasticsearch-exporter:v1.10.0, mem 64m)
   ▲ 15초마다 /metrics 수집 (job: elasticsearch, label pc=pc4)
Prometheus (scrape 15s, 규칙 평가 15s)
   ├─▶ 경보 규칙 → Alertmanager
   └─▶ Grafana (조회)
```

- 배치: `render.sh` 가 elasticsearch 와 prometheus 가 모두 있으면 **ES PC 에 es-exporter 를 자동 추가**한다. 자동 구성요소 맨 뒤(config-relay 다음)에 붙여 기존 고정 IP 가 바뀌지 않는다(시험 렌더: `es-exporter 172.28.0.35` 추가만).
- 실행 옵션: `--es.uri=http://elasticsearch:9200 --es.timeout=5s` 뿐 → exporter 기본 수집기만 켜진다.

## 2. 수집 지표

### 클러스터 상태 (`_cluster/health`)
| 지표 | 의미 |
|---|---|
| `elasticsearch_cluster_health_up` | exporter 가 ES 에 접속되는지 |
| `elasticsearch_cluster_health_status{color}` | green·yellow·red |
| `elasticsearch_cluster_health_number_of_nodes` | 노드 수 |
| `elasticsearch_cluster_health_active_shards` 외 `unassigned_shards`·`relocating_shards`·`initializing_shards` | 샤드 상태 |
| `elasticsearch_cluster_health_number_of_pending_tasks` | 대기 중인 클러스터 작업 |

### 노드 통계 (`_nodes/_local/stats`, 단일 노드라 사실상 전체)
| 분류 | 예 |
|---|---|
| JVM | `elasticsearch_jvm_memory_used_bytes{area="heap"}`, `elasticsearch_jvm_memory_max_bytes`, GC 횟수·시간 |
| 디스크 | `elasticsearch_filesystem_data_available_bytes`, `…_size_bytes` |
| 색인·검색 처리량(노드 합계) | 문서 수, 저장 용량, 색인 건수·시간, 검색 질의 건수·시간 |
| 스레드 풀 | 대기·거절(rejected) |
| 서킷 브레이커 | 메모리 한도 초과로 거절된 요청 |
| OS·프로세스 | CPU, 열린 파일 수 |

### 일부러 끈 것
- 색인별(`--es.indices`)·샤드별(`--es.shards`) 지표. 같은 ES 에 날짜별 로그 색인이 계속 생겨 켜면 시계열 수가 계속 는다.
- `products`·`sku-prices` 의 상태는 지표 대신 search-service **`GET /api/v1/internal/index-status`**(문서 수, 원천별 마지막 반영 시각·지연)로 본다.

## 3. 경보

| 경보 | 조건 | 등급 |
|---|---|---|
| ElasticsearchUnreachable | `elasticsearch_cluster_health_up == 0` 2분 | critical |
| ElasticsearchClusterRed | `elasticsearch_cluster_health_status{color="red"} == 1` 5분 | critical |
| ElasticsearchHeapHigh | 힙 사용률 > 90% 10분 | warning |
| AppDown(기존) | `up{job="elasticsearch"} == 0` — exporter 자체가 죽음 | 기존 규칙 |

- 단일 노드라 복제본을 둘 수 없으므로 **yellow 는 정상** → 경보 없음.
- 디스크는 기존 HostDiskLow·HostDiskCritical(host-disk, PC 단위)이 맡는다.

## 4. 현재 빠져 있는 것

1. **알림이 어디로도 가지 않는다.** `alertmanager.yml` receiver 가 `none` — 경보는 Prometheus·Alertmanager 화면에서만 보인다. Slack 등 발송은 명시 요청 시에만 설정한다(CLAUDE.md).
2. **Grafana ES 대시보드 없음.** 현재 `pmart-overview.json` 하나. Explore 에서 직접 조회는 가능. 필요하면 상태·힙·디스크·검색 지연 패널을 추가한다.

## 5. 배포 후 확인

```bash
curl -s http://192.168.0.40:9114/metrics | grep cluster_health_status
```
- Prometheus → Status → Targets 에서 `elasticsearch` job UP
- Prometheus → Alerts 에 Elasticsearch* 규칙 3개 로드

## 관련
- `docs/architecture/search-comparison.md` — 검색 메모 대비 상태(운영 항목)
- ADR-0031 — Elastic Stack(검색·로그)
