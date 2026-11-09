# 검색 평가 — 문서 경로 기준 (ollama:bge-m3@eval/1024)

> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).
> **문서 경로 기준**: 상위 5 안에 정답 문서(경로 앞부분 일치)의 청크가 하나라도 있으면 적중. 그 청크가 답을 담은 절인지는 보지 않는다 — 절 기준은 retrieval-section.md.

- 색인: 파일 75 · 청크 1966
- 색인 190.8초 · 질문당 검색 526 ms(임베딩 포함)
- 질문 25 · recall@5 **0.84** · MRR **0.675** · 임계값 통과 0건(NO_GROUNDING) 0

| id | 적중 순위 | 상위 근거(앞 3) |
|---|---|---|
| R1 | 1 | docs/business/Commerce-business-logic.md<br>docs/business/OMS-business-logic.md<br>docs/business/OMS-business-logic.md |
| R2 | 2 | docs/business/OMS-business-logic.md<br>docs/business/flows/cancel-vs-handover.md<br>docs/adr/0006-handover-fence-and-cancel-arbitration.md |
| R3 | 1 | docs/adr/0026-no-service-to-service-authentication.md<br>docs/adr/0026-no-service-to-service-authentication.md<br>docs/adr/0026-no-service-to-service-authentication.md |
| R4 | 1 | docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/glossary.md |
| R5 | 1 | docs/adr/0015-category-tree-follows-reference-data.md<br>docs/business/Commerce-business-logic.md<br>docs/architecture/service-map.md |
| Q01 | 1 | docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md |
| Q02 | 1 | docs/business/OMS-business-logic.md<br>docs/business/overview.md<br>docs/business/Commerce-business-logic.md |
| Q03 | 1 | docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q04 | 1 | docs/business/Delivery-business-logic.md<br>contracts/states/delivery-states.md<br>docs/business/TMS-business-logic.md |
| Q05 | 5 | docs/adr/0047-package-handover-fence.md<br>docs/adr/0014-master-data-and-initial-stock-loading.md<br>docs/business/glossary.md |
| Q06 | 1 | docs/business/Commerce-business-logic.md<br>docs/business/Delivery-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q07 | 1 | docs/adr/0029-config-repo-as-source-of-truth.md<br>docs/adr/0027-config-server-github-cipher.md<br>docs/adr/0013-deployment-lan-compose-and-helm.md |
| Q08 | 3 | docs/adr/0038-split-search-and-logs-elasticsearch.md<br>docs/adr/0034-kafka-event-history-retention-and-reindex.md<br>docs/adr/0031-elastic-stack-search-and-logs.md |
| Q09 | 2 | docs/business/overview.md<br>docs/architecture/service-map.md<br>docs/adr/0004-backoffice-ledgers-approval-case-center-master.md |
| Q10 | — | docs/business/WMS-business-logic.md<br>docs/adr/0040-mfc-inventory-model-v2.md<br>docs/adr/0047-package-handover-fence.md |
| Q11 | 2 | docs/architecture/service-map.md<br>docs/adr/0008-slot-capacity-ownership.md<br>docs/adr/0008-slot-capacity-ownership.md |
| Q12 | — | docs/adr/0042-mfc-open-decisions-lot-substitution-hold-ttl-location-code-bulk-inbound.md<br>docs/business/glossary.md<br>docs/business/OMS-business-logic.md |
| Q13 | — | docs/architecture/service-map.md<br>docs/business/OMS-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q14 | — | docs/adr/0007-tms-delivery-split-and-serviceability.md<br>docs/adr/0002-inventory-single-writer-per-center-type.md<br>docs/business/WMS-business-logic.md |
| Q15 | 2 | docs/business/TMS-business-logic.md<br>docs/adr/0010-time-utc-storage-seoul-business-dates.md<br>docs/adr/0010-time-utc-storage-seoul-business-dates.md |
| Q16 | 1 | docs/adr/0009-outbox-event-envelope-idempotency.md<br>docs/adr/0039-approval-application-receipt-event.md<br>docs/adr/0035-event-consumption-spring-cloud-stream.md |
| Q17 | 1 | docs/adr/0003-order-saga-orchestration.md<br>docs/adr/0003-order-saga-orchestration.md<br>docs/adr/0003-order-saga-orchestration.md |
| Q18 | 1 | docs/business/TMS-business-logic.md<br>docs/business/TMS-business-logic.md<br>docs/business/overview.md |
| Q19 | 1 | docs/adr/0018-operator-accounts-and-jwt-login.md<br>docs/architecture/service-map.md<br>docs/adr/0043-slice-6b-picking-packing.md |
| Q20 | 3 | docs/adr/0028-dev-base-image-temurin-jdk.md<br>docs/adr/0051-rag-customer-expansion-split-services-ollama.md<br>docs/adr/0012-image-registry-docker-hub-public.md |

## 실패·저순위 사례 (적중 없음 또는 3위 밖) — 상위 5 근거

### Q05 (5위)
- 질문: MFC 재고는 언제 차감하나요? 인계 시점인가요 출고 시점인가요?
- 정답 근거: docs/business/domain-map.md, docs/business/MFC-business-logic.md, docs/adr/0002-
1. 0.475 [CONFIRMED] docs/adr/0047-package-handover-fence.md — 결정 > 5
2. 0.506 [PROPOSAL] docs/adr/0014-master-data-and-initial-stock-loading.md — 결정 > 2
3. 0.485 [ADOPTED_DESIGN] docs/business/glossary.md — 3. 주문·결제
4. 0.465 [CONFIRMED] docs/business/TMS-business-logic.md — 10. MFC 도착·하차·인계 > 10.1 하차 절차 [확정]
5. 0.464 [CONFIRMED] docs/business/MFC-business-logic.md — 1. MFC 규모·시설·운영 설계 > 6. 부산 MFC점 주문·매출 추정 — 75,275가구 [확정 입력] > 6-3. 발주·입고 주기 최종 운영 시나리오 [이번 보완: 고정 방문 + 차등 발주]

### Q10 (적중 없음)
- 질문: MFC 재고 원장의 단일 작성자는 누구인가요?
- 정답 근거: docs/adr/0002-, docs/business/domain-map.md
1. 0.424 [PROPOSAL] docs/business/WMS-business-logic.md — 제5부 — 책임 정리·정합성 검수 > 37. 현재 정본과 참조 관계
2. 0.347 [CONFIRMED] docs/adr/0040-mfc-inventory-model-v2.md — ADR-0040 MFC 재고 모델 v2 — 위치·LOT·품질·업무상태별 잔량, LOT 단위 예약(FEFO·최소 잔여일), 단계별 검수·적치
3. 0.345 [CONFIRMED] docs/adr/0047-package-handover-fence.md — ADR-0047 라이더 포장 인계(슬라이스 6c) — fence 는 mfc-fulfillment, 배정은 배송 사건 투영, 유효시간은 센터 설정(기본 없음)
4. 0.389 [PROPOSAL] docs/business/WMS-business-logic.md — 제2부 — CDC WMS 상세 업무 기능 35개 (원문 기반·권한 재귀속) > 1. CDC의 정의·구현 범위·시스템 책임
5. 0.355 [ADOPTED_DESIGN] docs/architecture/service-map.md — 5. MFC (6) > mfc-inventory-service `[1단계]` — MFC 재고 **단일 쓰기 주체**

### Q12 (적중 없음)
- 질문: 주문 결제 사가의 타이머와 재고 예약 만료 규칙은?
- 정답 근거: docs/business/flows/order-checkout-saga.md
1. 0.515 [CONFIRMED] docs/adr/0042-mfc-open-decisions-lot-substitution-hold-ttl-location-code-bulk-inbound.md — 결정 > 2. 예약 유지 10분 — 확정, 설정값으로
2. 0.515 [ADOPTED_DESIGN] docs/business/glossary.md — 3. 주문·결제
3. 0.490 [ADOPTED_DESIGN] docs/business/OMS-business-logic.md — 7. 결제·주문 확정·만료 복구
4. 0.498 [PROPOSAL] docs/business/MFC-business-logic.md — 3. 재고관리 > 4. 업무 규칙 > IV-05 — 현물 예약·주문확정·만료
5. 0.473 [ADOPTED_DESIGN] docs/business/overview.md — 3. 도메인 책임 한눈에

### Q13 (적중 없음)
- 질문: 반품 교환 환불 사가는 어떻게 진행되나요?
- 정답 근거: docs/business/flows/return-exchange-refund.md
1. 0.534 [ADOPTED_DESIGN] docs/architecture/service-map.md — 2. Order & Payment (5) > claim-service `[1단계: 취소·반품 접수 / 2단계: 교환·회수 완성]`
2. 0.554 [PROPOSAL] docs/business/OMS-business-logic.md — 부록 A. OMS 하위 시스템 상세 및 시스템 연계 계약 > A.1 OMS 내부 업무 모듈의 구조와 책임 > A.1.12 교환·반품·회수 관리 모듈
3. 0.536 [PROPOSAL] docs/business/OMS-business-logic.md — 3. 주문·자원·실거래의 상태 전이와 종결
4. 0.511 [PROPOSAL] docs/business/BackOffice-business-logic.md — BO-14. 반품·교환·회수·후속 이행 심사
5. 0.477 [CONFIRMED] docs/business/OMS-business-logic.md — 11. 반품·교환·회수 > 11.1 접수와 자격

### Q14 (적중 없음)
- 질문: CDC 출고 간선 운송 MFC 수령 Transit 대사 흐름은?
- 정답 근거: docs/business/flows/replenishment-transfer.md
1. 0.556 [PROPOSAL] docs/adr/0007-tms-delivery-split-and-serviceability.md — 결정
2. 0.554 [PROPOSAL] docs/adr/0002-inventory-single-writer-per-center-type.md — 결정 > 5
3. 0.554 [PROPOSAL] docs/business/WMS-business-logic.md — 제2부 — CDC WMS 상세 업무 기능 35개 (원문 기반·권한 재귀속) > 14. 운송 결과·MFC 원수령·수량 대사 > CDC-F-024 MFC 실제 입고 확정 결과 인수·Transit 대사·복구
4. 0.553 [PROPOSAL] docs/business/WMS-business-logic.md — 제1부 — CDC WMS 책임·원장과 MFC 연계 > 0.3 수량 원장 불변식과 분산 대사 규칙
5. 0.543 [PROPOSAL] docs/business/WMS-business-logic.md — 제1부 — CDC WMS 책임·원장과 MFC 연계 > 0.4 CDC 중심 기능 범위 — 실행 모듈의 위치
