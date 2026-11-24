# 검색 색인 재색인 절차 (LAN)

- 근거:
  - ADR-0031: 색인은 Logstash 만
  - ADR-0034: commerce.product.v1·commerce.pricing.v1 무제한 보관, 재색인 = Kafka 전체 재생, 스냅샷 API 없음
  - `docs/architecture/search-comparison.md`
- 재색인 API(`/internal/reindex`)는 두지 않는다(501). 아래 절차로만 한다.
- **2026-11-06~ 구성(ADR-0038·0037)**: 검색 색인은 **pc3 의 `es-search`(http://192.168.0.222:9200)**, 넣는 주체는 **pc3 `logstash-search`**(컨테이너 `pmart-lan-logstash-search-1`), 색인 준비는 `es-init-search`. Kafka 는 3노드 클러스터(조회 컨테이너 `pmart-lan-kafka-1-1`, pc2). `reindex.sh` 는 이 이름을 쓴다. 아래 §2·§4 의 pc4·`elasticsearch`·`logstash`·`es-init`·`192.168.0.40:9200` 은 분리 전(~2026-11-05) 기록이다 — 지금은 각각 pc3·`es-search`·`logstash-search`·`es-init-search`·`192.168.0.222:9200` 으로 읽는다.
- 선행 조건(2026-11-04 충족):
  - 판매 상태 비교를 상품 행 버전으로 바꿈 — `ProductVersionPublished.rowVersion` 추가, Logstash·storefront 반영
  - 콘텐츠 보강 실패를 조용히 건너뛰지 않음 — 2번 더 시도한 뒤 `search-index.DLT` + 경보 `SearchIndexDeadLetter`

## 0. 언제 무엇을
| 상황 | 방법 |
|---|---|
| **매핑 변경**(새 필드·분석기) — 예 2026-11-04 `products-v2` → `products-v3` | §2 전체 재생으로 새 색인을 만든 뒤 별칭 교체 |
| **원천과 어긋남**(버그로 잘못 색인, 원인 수정 뒤) | §2 와 같음(같은 매핑이면 새 이름 `-v(n+1)`) |
| **일부 사건만 실패**(DLT 에 몇 건) | §3 부분 재처리 |
| 매핑이 같고 `_source` 만으로 충분한 복사 | ES `_reindex` 는 **Logstash 를 멈춘 동안에만**(쓰기 경합). 보통은 §2 를 쓴다 |

## 1. 바꾸는 곳
- `deploy/lan/elastic/search-index.env` → `render.sh` 가 logstash-search 환경변수로 넣는다.
  - `PMART_SEARCH_PRODUCTS_INDEX`: Logstash 가 쓰는 상품 색인. 평소에는 별칭 `products`.
  - `PMART_SEARCH_PRICES_INDEX`: 가격 색인. 평소에는 별칭 `sku-prices`.
  - `PMART_SEARCH_GROUP_ID`: Kafka 소비 그룹.
  - `PMART_SEARCH_DLT_TOPIC`: 실패 사건 토픽(`search-index.DLT`).
- 새 색인 정의는 search-service `src/main/resources/elasticsearch/<이름>.json`(별칭을 넣지 않는다).
  - es-init 과 search-service 는 **별칭이 없을 때만** 붙인다. 이전 버전을 가리키고 있으면 그대로 두고, 전환은 아래 §2-5 에서 사람이 한다.
- search-service 는 항상 별칭(`products`)을 읽는다. 재색인 중에는 이전 색인을 읽고, 별칭 교체 순간에 새 색인으로 바뀐다.

- **(2026-11-24 R-46) 1호점 센터 ID 바꿈 `MFC-SEED-01` → `MFC-BJG-01`**: 색인은 `_update_by_query` 로 고쳤지만 토픽 `mfc.sku-handling.v1` 의 지난 사건은 옛 ID 그대로다. 전체 재생으로 새 색인을 만든 뒤에는 `sku-handlings`·`products` 에 `MFC-SEED-01`(없는 센터)이 다시 생긴다 — switch 전에 같은 스크립트(centers 키·carriedMfcIds 의 옛 ID → 새 ID)로 고친다. 스크립트는 `docs/handoff/simulator/mfc/2026-11-24-reply-r46-center-id.md` §3.

## 2. 전체 재생으로 새 색인 만들기 (예: products-v3)

**스크립트(2026-11-05)**: `deploy/lan/elastic/reindex.sh` 가 아래 1~6 을 단계별로 실행하고, 단계마다 조건을 확인한다. 조건이 어긋나면 그 자리에서 멈춘다.
- 먼저 새 정의 파일(`products-vN.json`)과 `SearchIndex.INDICES` 를 바꿔 둔다.
- 새 소비 그룹 이름은 색인 번호에서 만든다(`products-v4` → `search-index-logstash-v4`).

| 명령 | 하는 일 |
|---|---|
| `reindex.sh status` | 별칭·색인 건수·소비 그룹 지연·영구 큐·DLT 를 보여 준다 |
| `reindex.sh check products-vN` | 1 의 사전 확인만 한다(바꾸는 것 없음). 확인 항목: 정의 파일, 새 색인·그룹이 없음, ES green, 보관·ES 경보 없음, Logstash 구독 토픽이 Kafka 에 모두 있음(없으면 `lan.sh deploy <Kafka PC>` — Kafka 노드 기동 뒤 topics-init), 지연 0·큐 0 |
| `reindex.sh start products-vN` | 사전 확인 → 백업 → env 변경(render 전) → es-init-search·logstash-search 재배포(pc3) |
| `reindex.sh wait [초]` | 4 의 조건(지연 0·큐 0·DLT 증가 없음·건수 ≥ 이전 색인)을 기다린 뒤 전 문서를 대조한다(`updatedAtMs` 제외, 재생 중 새로 생긴 상품은 따로 표시) |
| `reindex.sh switch` | 조건을 다시 확인한 뒤 5 의 별칭 교체 |
| `reindex.sh finish` | 6: env 를 별칭으로 되돌리고 logstash-search 재배포, 옛 그룹 삭제, 검색 ES PC(pc3)의 옛 정의 파일 삭제 |
| `reindex.sh rollback` | 7: 별칭을 이전 색인으로 되돌린다 |
| `reindex.sh drop products-vM` | 옛 색인 삭제. 별칭이나 Logstash 가 쓰지 않을 때만 |

- 진행 상태는 `deploy/lan/.reindex.state`(git 제외)에 둔다.
- `sku-prices`·`sku-handlings`(SKU 별 가격·센터 취급)는 별칭 그대로 둔다. 새 그룹이 같은 사건을 다시 읽어도 버전 비교로 바뀌지 않고, 새 상품 색인은 상품 사건을 받을 때 두 문서를 찾아 붙인다.
- search-service 새 이미지는 switch 뒤에 배포한다.
- finish 뒤 바뀐 그룹 값을 `search-index.env` 에 커밋한다.
1. **사전 확인**
   - Kafka 보관이 무제한인지 본다(`KafkaEventRetentionNotUnlimited`·`KafkaEventHistoryTruncated` 경보 없음).
   - 백업: `deploy/lan/kafka/backup.sh /d/pmart-backups/kafka before-reindex`.
   - ES green, 지금 소비 그룹 지연 0.
2. **`search-index.env` 를 먼저 바꾼다**(render 전에. 커밋하지 않는 임시 값, 6단계에서 되돌린다):
   - `PMART_SEARCH_PRODUCTS_INDEX=products-v3`
   - `PMART_SEARCH_GROUP_ID=search-index-logstash-v3`
   - 가격 색인 매핑이 그대로면 `sku-prices` 는 두어도 된다(같은 가격 버전은 그대로 무시).
   - **순서 주의**: Logstash 설정(search-index.conf)도 render 가 search-service 에서 복사한다. 새 필드를 쓰는 설정이 env 를 바꾸기 전에 배포되면 옛 색인(strict 매핑)에 쓰다가 모두 실패한다.
3. **새 이미지 배포와 새 색인 채우기**(매핑·사건이 바뀐 경우): product-service(새 사건 필드), storefront, search-service jib.
   - `lan.sh render` → `deploy pc4`: es-init 이 `products-v3` 를 **빈 채로** 만든 뒤(별칭은 그대로 v2) Logstash 가 뜬다.
   - 그다음 `deploy pc2 product-service storefront-service search-service`.
   - 새 그룹은 두 토픽을 처음부터(earliest) 읽는다.
   - 이 동안 v2 는 새 사건을 받지 않는다(검색 결과가 잠시 이전 상태). 운영에서는 두 파이프라인을 나란히 돌린다(후속 설계).
4. **반영 완료 확인**(모두 만족해야 다음 단계)
   - 소비 그룹 `search-index-logstash-v3` 지연 0(kafka-consumer-groups `--describe`).
   - Logstash 영구 큐 비어 있음: pc4 에서 `docker exec pmart-lan-logstash-1 curl -s localhost:9600/_node/stats/pipelines/search-index` 의 queue events 0(9600 은 외부에 열지 않았다). 영구 큐라 오프셋 커밋이 ES 쓰기보다 먼저 일어나므로 지연 0 만으로는 부족하다.
   - `search-index.DLT` 새 레코드 없음(있으면 §3 으로 원인부터).
   - 건수 대조: `GET products-v3/_count` = `GET products/_count`(v2) = product-service 상품 수, 판매중지 제외 기준이 같은지.
   - 표본 몇 개 상품의 `saleState`·`basePrice`·`name` 이 v2 와 같은지(새 필드 `saleStateRowVersion`·`filters` 는 v3 에만).
5. **별칭 교체**(원자적, 한 요청)
   ```bash
   curl -s -X POST http://192.168.0.40:9200/_aliases -H 'Content-Type: application/json' -d '{
     "actions": [
       { "remove": { "index": "products-v2", "alias": "products" } },
       { "add":    { "index": "products-v3", "alias": "products" } }
     ] }'
   ```
6. **정리**
   - `search-index.env` 를 `PMART_SEARCH_PRODUCTS_INDEX=products`(다시 별칭)로 되돌리고 `PMART_SEARCH_GROUP_ID=search-index-logstash-v3` 는 그대로 둔다 → render → `deploy pc4 logstash`.
   - 옛 소비 그룹 `search-index-logstash` 삭제(소비자 없음 확인 뒤).
   - `products-v2` 는 며칠 두었다가 삭제한다(되돌림용).
   - **옛 정의 파일을 지운다**: `lan.sh deploy` 의 scp 는 파일을 더하기만 한다. Elasticsearch PC 의 `pmart-lan\elastic\indices\products-v2.json` 이 남아 있으면 es-init 이 v2 를 지운 뒤에도 다시 만든다(2026-11-04 실제로 남아 있었음 → 삭제).
   - 바뀐 소비 그룹 값(`search-index-logstash-v3`)은 `search-index.env` 에 커밋한다. 다음 render 가 옛 그룹으로 되돌리면 처음부터 다시 읽는다.
   - search-service `SearchIndex.INDICES`·정의 파일은 이미 v3 다.
7. **되돌림**: 별칭을 v2 로 다시 교체한다(§2-5 의 remove/add 반대). v2 는 3단계부터 사건을 받지 않았으므로, 되돌린 뒤 원인을 고쳐 다시 §2 를 한다.

### 2-7. 순위 신호 다시 내기 (products-v6 부터, ADR-0072)
- 신호 토픽 `commerce.product-ranking.v1` 은 무제한 보관이 아니다 — 재생만으로는 모든 상품의 신호가 오지 않을 수 있다. 별칭을 새 색인으로 옮긴 뒤 한 번:
  `curl -s -X POST -H "Idempotency-Key: $(uuidgen)" -H "X-Source-System: pc1-ops" http://192.168.0.34:8106/api/v1/internal/storefront/ranking-signal-republications` → `{published: N}`(변경 요청 헤더 필수). **재생 시작(start) 전에** 한 번 부르면 새 그룹이 처음부터 읽을 때 모두 들어온다(2026-11-20 v6 때 그렇게 함). Logstash 가 version 이 큰 것만 반영하므로 여러 번 불러도 된다.

## 3. 부분 재처리 (DLT)
- DLT 레코드: `search-index.DLT`, 키 = 상품 id.
  - 본문의 `pmartDeadLetter{op, id, tags, topic, partition, offset, at}` 가 원래 위치를 담는다.
  - 원래 사건은 이력 토픽에 그대로 있다.
- 원인(예 product-content-service 장애·게시 지연)을 고친 뒤, 방법은 둘 중 하나다:
  - **그 상품의 사건이 새로 나오게 한다**: 콘텐츠 재게시. 가장 간단하다.
  - **소비 그룹 오프셋을 DLT 의 가장 이른 offset 으로 되돌린다**: Logstash 를 멈춘 동안 `kafka-consumer-groups --reset-offsets --to-offset <n> --topic commerce.product.v1:<partition>`. 사이 사건이 다시 처리되지만 버전 비교로 멱등이다.
- DLT 를 다 처리했으면 경보는 30분 뒤 저절로 풀린다(증가 기준).

## 4. 하지 않는 것
- 적용된 색인의 매핑을 고치지 않는다(새 버전을 만든다, search-service CLAUDE.md).
- 재생 중 별칭을 미리 옮기지 않는다(빈 색인을 검색하게 된다).
- Log Compaction 을 켜지 않는다. 같은 키에 사건 종류가 여러 개라 이력이 사라진다(ADR-0034).

## 5. 실행 기록
| 날짜 | 전환 | 결과 |
|---|---|---|
| 2026-11-04 22:42~22:52 KST | `products-v2` → `products-v3`, 그룹 `search-index-logstash` → `-v3` (커밋 0013eb7, 백업 `/d/pmart-backups/kafka/20261104T134205Z-before-reindex-v3`) | 사건 1162건(product 887·pricing 275)을 약 15초에 재생해 상품 275건. 지연 0, 영구 큐 0, DLT 0, 건수 275 = 275, 전 문서 필드 대조 결과 `updatedAtMs`(색인 시각)만 다름. `filters` 는 0건 — LAN 상품에 필터 옵션이 하나도 없음(`product_filter_option` 0행), `saleStateRowVersion` 은 판매 상태 사건이 있던 7건. LAN 확인(판매 상태·필터) 뒤 같은 날 `products-v2` 삭제(되돌림은 v2 대신 새 색인 재생 — 15초) |
| 2026-11-05 06:09~06:15 KST | `products-v3` → `products-v4`, 그룹 `-v3` → `-v4`, 새 색인 `sku-handlings-v1`(커밋 7896f3e, **reindex.sh 첫 실행**, 백업 `/d/pmart-backups/kafka/20261104T210925Z-before-reindex-products-v4`) | 앞서 pc2 `lan.sh deploy pc2 kafka`(토픽 `mfc.sku-handling.v1` 생성·무제한), pc3 mfc-inventory 배포 → 취급 재발행 275건. 사건 1440건(product 890·pricing 275·handling 275)을 약 40초에 재생해 275건, 전 문서 대조 차이 없음. 새 필드 `carriedMfcIds` 275건(MFC-SEED-01)·`nameChosung` 275건. switch → search-service 배포 → finish(옛 그룹 삭제, pc4 옛 정의 파일 정리). 중간에 `KafkaEventRetentionNotUnlimited`(새 토픽, 지표 스크립트가 토픽 생성 전에 측정)로 check 가 한 번 멈춤 → 토픽 생성 뒤 풀림. `products-v3` 은 남김 |
| 2026-11-05 06:49~06:55 KST | `products-v4` → `products-v5`(초성 앞부분 `nameChosung.edge`), 그룹 `-v4` → `-v5` (커밋 a806803, 백업 `/d/pmart-backups/kafka/20261104T214951Z-before-reindex-products-v5`) | reindex.sh 두 번째 실행, 멈춤 없이 끝까지. 사건 1440건 약 40초, 전 문서 대조 차이 없음. switch → search-service 배포 → "ㄷㅂ" 첫 결과가 두부, "ㅅㅋㄹ" 상위 3건 생크림 → finish. `products-v3`·`products-v4` 남김 |
- 2026-11-20 00:00~00:06 v5 → v6(ADR-0072 순위 신호 칸, 사용자 "응 해줘"): storefront 배포·신호 504건 다시 냄 → check → start(백업 `20261119T145836Z-before-reindex-products-v6`, 첫 시도는 PC 2 이름 조회 일시 실패로 사전 확인에서 멈춤 → 다시) → wait(504/504, 다른 필드 없음) → switch → search-service 배포 → finish(그룹 search-index-logstash-v6, 옛 그룹 삭제). products-v5.json 삭제, products-v5 색인은 며칠 두었다 drop.
- 2026-11-20 03:31~03:42 v6 → v7(ADR-0073 단계 A 동의어 분석기, 사용자 "여기서 바로 배포해" — PR 세션): 순위 신호 504건 다시 냄 → check → start → wait(504/504, 다른 필드 없음, 동의어 집합 pmart-product-synonyms 200) → switch → search-service e93d5783 배포(ERROR 0) → finish(그룹 search-index-logstash-v7, 옛 그룹 삭제). products-v6.json 삭제, products-v6 색인은 며칠 두었다 drop.
- 2026-11-22 12:10~12:19 v7 → v8(원산지 originType·originText·설명 description + nori 사용자 사전 동의어 낱말, 사용자 "응 해줘"): product-content 3acc81e6 배포(카드 include=search) → 순위 신호 504건 다시 냄 → check → start → wait(504/504, 다른 필드 없음 — v8 originType 국내 152·수입 275·없음 77, 설명 503, 필터 463) → switch → search-service 3acc81e6 배포 → finish(그룹 search-index-logstash-v8, 옛 그룹 삭제). products-v7.json 삭제, products-v7 색인은 며칠 두었다 drop.
