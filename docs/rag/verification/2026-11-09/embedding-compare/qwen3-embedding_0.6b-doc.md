# 검색 평가 — 문서 경로 기준 (ollama:qwen3-embedding:0.6b@eval/1024)

> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).
> **문서 경로 기준**: 상위 5 안에 정답 문서(경로 앞부분 일치)의 청크가 하나라도 있으면 적중. 그 청크가 답을 담은 절인지는 보지 않는다 — 절 기준은 retrieval-section.md.

- 색인: 파일 75 · 청크 1966
- 색인 201.4초 · 질문당 검색 335 ms(임베딩 포함)
- 질문 25 · recall@5 **0.84** · MRR **0.700** · 임계값 통과 0건(NO_GROUNDING) 0

| id | 적중 순위 | 상위 근거(앞 3) |
|---|---|---|
| R1 | 1 | docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/OMS-business-logic.md |
| R2 | 3 | docs/business/OMS-business-logic.md<br>docs/business/MFC-business-logic.md<br>docs/business/flows/cancel-vs-handover.md |
| R3 | 1 | docs/adr/0026-no-service-to-service-authentication.md<br>docs/adr/0026-no-service-to-service-authentication.md<br>docs/adr/0026-no-service-to-service-authentication.md |
| R4 | 1 | docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/glossary.md |
| R5 | 1 | docs/business/Commerce-business-logic.md<br>docs/adr/0015-category-tree-follows-reference-data.md<br>docs/adr/0051-rag-customer-expansion-split-services-ollama.md |
| Q01 | 1 | docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md |
| Q02 | 1 | docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/overview.md |
| Q03 | 1 | docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q04 | 2 | docs/adr/0006-handover-fence-and-cancel-arbitration.md<br>docs/business/Delivery-business-logic.md<br>contracts/states/delivery-states.md |
| Q05 | — | docs/adr/0047-package-handover-fence.md<br>docs/adr/0014-master-data-and-initial-stock-loading.md<br>docs/business/TMS-business-logic.md |
| Q06 | 1 | docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/Delivery-business-logic.md |
| Q07 | 2 | docs/adr/0004-backoffice-ledgers-approval-case-center-master.md<br>docs/adr/0029-config-repo-as-source-of-truth.md<br>docs/adr/0029-config-repo-as-source-of-truth.md |
| Q08 | 3 | docs/adr/0038-split-search-and-logs-elasticsearch.md<br>docs/architecture/service-map.md<br>docs/adr/0031-elastic-stack-search-and-logs.md |
| Q09 | 1 | docs/adr/0001-product-master-in-product-service.md<br>docs/architecture/service-map.md<br>docs/business/MFC-business-logic.md |
| Q10 | — | docs/business/MFC-business-logic.md<br>docs/business/MFC-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q11 | 1 | docs/adr/0008-slot-capacity-ownership.md<br>docs/architecture/service-map.md<br>docs/business/OMS-business-logic.md |
| Q12 | 3 | docs/adr/0042-mfc-open-decisions-lot-substitution-hold-ttl-location-code-bulk-inbound.md<br>docs/business/glossary.md<br>docs/business/flows/order-checkout-saga.md |
| Q13 | — | docs/architecture/service-map.md<br>docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md |
| Q14 | — | docs/business/WMS-business-logic.md<br>docs/adr/0002-inventory-single-writer-per-center-type.md<br>docs/business/WMS-business-logic.md |
| Q15 | 2 | docs/business/TMS-business-logic.md<br>docs/adr/0010-time-utc-storage-seoul-business-dates.md<br>docs/adr/0041-per-product-shelf-life-guarantee.md |
| Q16 | 1 | docs/adr/0009-outbox-event-envelope-idempotency.md<br>docs/adr/0034-kafka-event-history-retention-and-reindex.md<br>docs/adr/0035-event-consumption-spring-cloud-stream.md |
| Q17 | 1 | docs/adr/0003-order-saga-orchestration.md<br>docs/adr/0003-order-saga-orchestration.md<br>docs/adr/0003-order-saga-orchestration.md |
| Q18 | 1 | docs/business/TMS-business-logic.md<br>docs/business/TMS-business-logic.md<br>docs/business/overview.md |
| Q19 | 1 | docs/adr/0018-operator-accounts-and-jwt-login.md<br>docs/architecture/service-map.md<br>docs/adr/0018-operator-accounts-and-jwt-login.md |
| Q20 | 1 | docs/adr/0012-image-registry-docker-hub-public.md<br>docs/adr/0016-object-storage-seaweedfs-s3.md<br>docs/adr/0012-image-registry-docker-hub-public.md |

## 실패·저순위 사례 (적중 없음 또는 3위 밖) — 상위 5 근거

### Q05 (적중 없음)
- 질문: MFC 재고는 언제 차감하나요? 인계 시점인가요 출고 시점인가요?
- 정답 근거: docs/business/domain-map.md, docs/business/MFC-business-logic.md, docs/adr/0002-
1. 0.530 [CONFIRMED] docs/adr/0047-package-handover-fence.md — 결정 > 5
2. 0.538 [PROPOSAL] docs/adr/0014-master-data-and-initial-stock-loading.md — 결정 > 2
3. 0.490 [CONFIRMED] docs/business/TMS-business-logic.md — 10. MFC 도착·하차·인계 > 10.1 하차 절차 [확정]
4. 0.477 [CONFIRMED] docs/business/TMS-business-logic.md — 3. 운영 시간표 > 3.1 역산 근거 [확정]
5. 0.483 [ADOPTED_DESIGN] docs/architecture/service-map.md — 5. MFC (6) > mfc-inventory-service `[1단계]` — MFC 재고 **단일 쓰기 주체**

### Q10 (적중 없음)
- 질문: MFC 재고 원장의 단일 작성자는 누구인가요?
- 정답 근거: docs/adr/0002-, docs/business/domain-map.md
1. 0.521 [PROPOSAL] docs/business/MFC-business-logic.md — 2. 작업관리 > 1. 목적·책임과 주요 용어
2. 0.511 [PROPOSAL] docs/business/MFC-business-logic.md — 1. MFC 규모·시설·운영 설계 > MFC의 주요 업무
3. 0.477 [CONFIRMED] docs/business/OMS-business-logic.md — 6. MFC 배정·예약·시간대 확보
4. 0.467 [ADOPTED_DESIGN] docs/architecture/service-map.md — 5. MFC (6) > mfc-inventory-service `[1단계]` — MFC 재고 **단일 쓰기 주체**
5. 0.484 [PROPOSAL] docs/adr/0014-master-data-and-initial-stock-loading.md — 결정 > 2

### Q13 (적중 없음)
- 질문: 반품 교환 환불 사가는 어떻게 진행되나요?
- 정답 근거: docs/business/flows/return-exchange-refund.md
1. 0.532 [ADOPTED_DESIGN] docs/architecture/service-map.md — 2. Order & Payment (5) > claim-service `[1단계: 취소·반품 접수 / 2단계: 교환·회수 완성]`
2. 0.561 [PROPOSAL] docs/business/OMS-business-logic.md — 부록 A. OMS 하위 시스템 상세 및 시스템 연계 계약 > A.1 OMS 내부 업무 모듈의 구조와 책임 > A.1.12 교환·반품·회수 관리 모듈
3. 0.484 [CONFIRMED] docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회 > 13.1 취소·인계 결과가 확인 중일 때의 안내
4. 0.521 [PROPOSAL] docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회 > 13.3 반품·교환 신청부터 종료까지
5. 0.500 [PROPOSAL] docs/business/BackOffice-business-logic.md — BO-14. 반품·교환·회수·후속 이행 심사

### Q14 (적중 없음)
- 질문: CDC 출고 간선 운송 MFC 수령 Transit 대사 흐름은?
- 정답 근거: docs/business/flows/replenishment-transfer.md
1. 0.612 [PROPOSAL] docs/business/WMS-business-logic.md — 제1부 — CDC WMS 책임·원장과 MFC 연계 > 0.3 수량 원장 불변식과 분산 대사 규칙
2. 0.590 [PROPOSAL] docs/adr/0002-inventory-single-writer-per-center-type.md — 결정 > 5
3. 0.594 [PROPOSAL] docs/business/WMS-business-logic.md — 제2부 — CDC WMS 상세 업무 기능 35개 (원문 기반·권한 재귀속) > 14. 운송 결과·MFC 원수령·수량 대사 > CDC-F-024 MFC 실제 입고 확정 결과 인수·Transit 대사·복구
4. 0.578 [PROPOSAL] docs/adr/0007-tms-delivery-split-and-serviceability.md — 결정
5. 0.565 [ADOPTED_DESIGN] docs/business/overview.md — 1. 한 줄 요약
