# Debezium 사용 방식 — pmart(Outbox CDC) vs Eum(테이블 CDC 혼용)

작성 2026-11-03. 근거: pmart `docs/architecture/architecture-design.md` §5.2, ADR-0009, ADR-0031, `deploy/local/connector-registrar.sh`; Eum `eum_backend/docker-compose/debezium/register-connector.sh`(읽기만).

## 1. 요약

| | pmart | Eum |
|---|---|---|
| Debezium 역할 | **운반 수단만** — 각 서비스 `outbox_event` 를 Kafka 로 | 운반(Outbox) + **업무 테이블 복제**(검색·분석) |
| 검색 색인 입력 | 계약된 도메인 사건(`commerce.product.v1`·`commerce.pricing.v1`) → Logstash → ES | `products`·`product_sku` 등 행 변경 → ES |
| 서비스 간 사가 | Outbox 사건 | Outbox 사건(주문·재고·결제) |

즉 "Eum 은 이벤트가 아니다"는 **검색·분석 경로에 한해** 맞다. 사가 경로는 두 프로젝트 모두 Outbox 사건이다.

## 2. pmart — Outbox CDC

```
product-service (한 트랜잭션)
 ├─ products 갱신                  ← 업무 테이블: Debezium 이 보지 않음
 └─ outbox_event INSERT + DELETE   ← Debezium 이 보는 유일한 테이블
        │ PostgreSQL WAL(논리 복제, publication pmart_outbox)
        ▼
 Debezium Connect ─ Outbox Event Router SMT
        │ topic 필드 → 토픽, aggregateId → 키, eventType·traceId·correlationId → 헤더
        ▼
 Kafka commerce.product.v1 ─▶ Logstash(search-index) → Elasticsearch
                          └─▶ cart·order·storefront 등 다른 구독자
```

- 결정: 2026-10-23(ADR-0009). 애플리케이션 폴링 릴레이 없음.
- 서비스마다 커넥터 1개, 대상은 `outbox_event` 뿐(10/24 기준 28개 RUNNING). `watch.sh` 점검 대상.
- delete-after-insert: 테이블은 늘 비어 있고 WAL 의 INSERT 로 발행된다.
- 발행 전 `contracts/events` JSON Schema 검증(ENFORCE). Schema Registry 없음.
- 커넥터 스냅샷은 `snapshot no_data` — Debezium 으로 과거를 다시 흘리지 않는다. 재처리는 Kafka 이력으로.

## 3. Eum — 커넥터 8개

| 용도 | 테이블 | 방식 |
|---|---|---|
| 상품 검색 | `products`, `product_sku` | 테이블 CDC |
| 공지·FAQ 검색 | `notices`, `faq` | 테이블 CDC |
| 리뷰 검색 | `reviews` | 테이블 CDC |
| 주문 분석 | `orders`, `order_details` | 테이블 CDC → Logstash |
| 주문 사가 | `order_outbox` | Outbox + EventRouter |
| 재고 사가 | `inventory_outbox` | Outbox + EventRouter |
| 결제 사가 | `outbox_events` | Outbox + EventRouter |

(주문 DB 는 outbox·분석 커넥터 2개라 표 7행에 커넥터 8개.)

## 4. 테이블 CDC 와 Outbox CDC 비교

| | 테이블 CDC (Eum 검색) | Outbox CDC (pmart) |
|---|---|---|
| Kafka 에 실리는 것 | 행의 변경 전·후 값(DB 컬럼 그대로) | 계약된 도메인 사건(`ProductContentPublished`, `PriceVersionEffective` …) |
| 구독자 결합도 | 강함 — 컬럼 변경이 색인 처리를 깨뜨림 | 약함 — 사건 계약만 지키면 테이블은 자유 |
| 원천 서비스 부담 | 없음(자동) | 사건 설계·Outbox 기록 |
| 업무 의미 | 없음 — "행이 바뀜". 초안/게시본 구분은 구독자가 컬럼을 해석 | 있음 — "게시됨", "발효됨" 같은 확정 사실만 |
| 다른 서비스 내부 규칙 | 구독자가 복제해야 함 | 원천에만 있음 |
| 재색인 | 쉬움 — 커넥터 스냅샷으로 현재 테이블 전체 재전송 | Kafka 이력 재생(보관 기간 필요) 또는 별도 스냅샷 |

## 5. pmart 가 테이블 CDC 를 쓰지 않는 이유

1. **승인·발효 흐름.** 상품·콘텐츠·가격은 승인과 발효를 거쳐야 공개된다. 테이블 CDC 로 받으면 초안·승인 대기·예약 가격까지 흘러오고, 검색 쪽이 "지금 공개된 상태인가"를 판단하려면 product·pricing 의 내부 규칙을 복제해야 한다.
2. **데이터 소유 원칙.** CLAUDE.md "다른 서비스의 DB·엔티티에 직접 접근하지 않는다. API 또는 이벤트만." 테이블 CDC 는 사실상 다른 서비스 테이블 구조에 의존하는 것이다.
3. **구독자가 여럿.** 같은 사건을 cart·order·storefront·검색이 함께 쓴다. 사건 하나로 모두를 만족시키는 편이 테이블별 해석을 각자 하는 것보다 일관된다.

## 6. 재색인에 주는 의미

- Eum 방식의 실질 이점은 **재색인이 쉽다**는 점이다(커넥터 스냅샷).
- pmart 에서 같은 효과를 얻는 방법(2026-11-03 합의, 승인 대기): 상품·가격 토픽 보관 기간을 **무제한**으로 두고, 어긋남 복구는 새 소비 그룹이 Kafka 를 처음부터 읽어 새 색인을 만든 뒤 별칭 교체. 매핑 변경은 ES `_reindex`. compaction 은 쓰지 않는다(같은 키에 사건 종류가 여러 개).
- 스냅샷 + 따라잡기 설계(파티션별 시작 오프셋, 병렬 소비 그룹·색인, EFFECTIVE 가격 스냅샷)는 운영 출시·이력 재생 시간 과다·무제한 보관 불가 중 하나가 생기면 도입한다.
- Debezium 이 멈추면 사건이 WAL 에 쌓여 발행이 늦어진다 — 검색 `GET /internal/index-status` 지연이 커지는 원인 중 하나.

## 7. 관련 문서

- `docs/architecture/search-comparison.md` — 검색 메모 대비 현재 상태
- `docs/adr/` ADR-0009(Outbox·Debezium), ADR-0031(Elasticsearch·Logstash 색인)
- `docs/architecture/events.md` — 이벤트 카탈로그
