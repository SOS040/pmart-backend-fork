# 검색 기준선 평가 — 문서 경로 기준 (local-hash-ngram@1/384)

> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).
> **문서 경로 기준**: 상위 5 안에 정답 문서(경로 앞부분 일치)의 청크가 하나라도 있으면 적중. 그 청크가 답을 담은 절인지는 보지 않는다 — 절 기준은 retrieval-section.md.

- 색인: 파일 60 · 청크 1824
- 질문 25 · recall@5 **0.76** · MRR **0.628** · 임계값 통과 0건(NO_GROUNDING) 0

| id | 적중 순위 | 상위 근거(앞 3) |
|---|---|---|
| R1 | 1 | docs/business/Commerce-business-logic.md<br>docs/business/OMS-business-logic.md<br>docs/business/overview.md |
| R2 | 5 | docs/business/OMS-business-logic.md<br>docs/business/OMS-business-logic.md<br>docs/business/OMS-business-logic.md |
| R3 | 1 | docs/adr/0026-no-service-to-service-authentication.md<br>docs/adr/0026-no-service-to-service-authentication.md<br>docs/adr/0026-no-service-to-service-authentication.md |
| R4 | 1 | docs/business/OMS-business-logic.md<br>docs/business/glossary.md<br>docs/business/Commerce-business-logic.md |
| R5 | 1 | docs/business/Commerce-business-logic.md |
| Q01 | 1 | docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md |
| Q02 | 1 | docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md |
| Q03 | 1 | docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/Commerce-business-logic.md |
| Q04 | 1 | docs/business/Delivery-business-logic.md<br>docs/business/Delivery-business-logic.md<br>docs/business/Delivery-business-logic.md |
| Q05 | — | docs/business/TMS-business-logic.md<br>docs/business/WMS-business-logic.md<br>docs/business/overview.md |
| Q06 | 2 | docs/adr/0005-payment-service-and-pg-source-of-truth.md<br>docs/business/Commerce-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q07 | 4 | docs/adr/0026-no-service-to-service-authentication.md<br>docs/adr/0004-backoffice-ledgers-approval-case-center-master.md<br>docs/adr/0032-eureka-service-discovery-local-lan.md |
| Q08 | 1 | docs/adr/0031-elastic-stack-search-and-logs.md<br>docs/business/Commerce-business-logic.md<br>docs/business/WMS-business-logic.md |
| Q09 | 4 | docs/adr/0032-eureka-service-discovery-local-lan.md<br>docs/adr/0004-backoffice-ledgers-approval-case-center-master.md<br>docs/adr/0004-backoffice-ledgers-approval-case-center-master.md |
| Q10 | — | docs/business/TMS-business-logic.md<br>docs/business/WMS-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q11 | 1 | docs/adr/0008-slot-capacity-ownership.md<br>docs/architecture/service-map.md<br>docs/business/BackOffice-business-logic.md |
| Q12 | — | docs/business/OMS-business-logic.md<br>docs/business/OMS-business-logic.md<br>docs/business/glossary.md |
| Q13 | — | docs/business/OMS-business-logic.md<br>docs/business/Commerce-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q14 | — | docs/adr/0002-inventory-single-writer-per-center-type.md<br>docs/business/WMS-business-logic.md<br>docs/business/BackOffice-business-logic.md |
| Q15 | — | docs/business/OMS-business-logic.md<br>docs/business/WMS-business-logic.md<br>docs/business/WMS-business-logic.md |
| Q16 | 1 | docs/adr/0009-outbox-event-envelope-idempotency.md<br>docs/adr/0010-time-utc-storage-seoul-business-dates.md<br>docs/business/MFC-business-logic.md |
| Q17 | 1 | docs/adr/0003-order-saga-orchestration.md |
| Q18 | 1 | docs/business/TMS-business-logic.md<br>docs/business/TMS-business-logic.md<br>docs/business/OMS-business-logic.md |
| Q19 | 1 | docs/adr/0018-operator-accounts-and-jwt-login.md<br>docs/architecture/service-map.md<br>docs/adr/0018-operator-accounts-and-jwt-login.md |
| Q20 | 2 | docs/adr/0028-dev-base-image-temurin-jdk.md<br>docs/adr/0012-image-registry-docker-hub-public.md<br>docs/adr/0012-image-registry-docker-hub-public.md |

## 실패·저순위 사례 (적중 없음 또는 3위 밖) — 상위 5 근거

### R2 (5위)
- 질문: 주문 취소와 라이더 인계가 겹치면 어떤 절차를 따르나요?
- 정답 근거: docs/business/flows/cancel-vs-handover.md
1. 0.246 [PROPOSAL] docs/business/OMS-business-logic.md — 부록 A. OMS 하위 시스템 상세 및 시스템 연계 계약 > A.1 OMS 내부 업무 모듈의 구조와 책임 > A.1.11 전체·부분 취소 조정 모듈
2. 0.217 [PROPOSAL] docs/business/OMS-business-logic.md — 8. 피킹·결품·취소 조정 > 8.4 취소 접수와 현장 중지·인계 판정의 분리
3. 0.217 [PROPOSAL] docs/business/OMS-business-logic.md — 부록 A. OMS 하위 시스템 상세 및 시스템 연계 계약 > A.5 대표 연계 시나리오 > A.5.4 고객 취소와 라이더 인계 경합
4. 0.204 [PROPOSAL] docs/business/OMS-business-logic.md — 8. 피킹·결품·취소 조정 > 8.3 취소와 인계 경합
5. 0.192 [PROPOSAL] docs/business/flows/cancel-vs-handover.md — 0. 원칙

### Q05 (적중 없음)
- 질문: MFC 재고는 언제 차감하나요? 인계 시점인가요 출고 시점인가요?
- 정답 근거: docs/business/domain-map.md, docs/business/MFC-business-logic.md, docs/adr/0002-
1. 0.254 [CONFIRMED] docs/business/TMS-business-logic.md — 10. MFC 도착·하차·인계 > 10.1 하차 절차 [확정]
2. 0.275 [PROPOSAL] docs/business/WMS-business-logic.md — 제3부 — CDC WMS의 5대 식별자 상세 정책과 센터 간 보존계약 > 부록 3. LPN/BOX/PLT 화물 구성 정책 — V6.6 원문 세부 정책 > 정책 15.8 CDC → MFC 보충 출고 및 검수
3. 0.253 [ADOPTED_DESIGN] docs/business/overview.md — 2. 두 개의 물류 흐름
4. 0.222 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 21. 상세 처리 절차 (P01~P12) > P08 — MFC 하차·인계
5. 0.221 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 13. 재고와 운송의 경계 [보완]

### Q07 (4위)
- 질문: 서비스 설정의 정본은 어디에 두나요?
- 정답 근거: docs/adr/0029-, docs/adr/0027-
1. 0.256 [CONFIRMED] docs/adr/0026-no-service-to-service-authentication.md — 결과
2. 0.269 [PROPOSAL] docs/adr/0004-backoffice-ledgers-approval-case-center-master.md — 결과
3. 0.219 [CONFIRMED] docs/adr/0032-eureka-service-discovery-local-lan.md — ADR-0032 local·LAN 서비스 등록·조회는 Eureka — 인스턴스는 하나씩, 운영(Kubernetes)은 Service DNS
4. 0.214 [CONFIRMED] docs/adr/0027-config-server-github-cipher.md — 배경
5. 0.211 [CONFIRMED] docs/adr/0027-config-server-github-cipher.md — ADR-0027 LAN 설정은 Spring Cloud Config Server(GitHub 저장소, {cipher})로 부팅 때 받는다

### Q09 (4위)
- 질문: 상품 마스터 정본은 어느 서비스가 소유하나요?
- 정답 근거: docs/adr/0001-, docs/business/domain-map.md, docs/architecture/service-map.md
1. 0.238 [CONFIRMED] docs/adr/0032-eureka-service-discovery-local-lan.md — 결과
2. 0.245 [PROPOSAL] docs/adr/0004-backoffice-ledgers-approval-case-center-master.md — 배경
3. 0.235 [PROPOSAL] docs/adr/0004-backoffice-ledgers-approval-case-center-master.md — 결정
4. 0.207 [ADOPTED_DESIGN] docs/architecture/service-map.md — 서비스 책임 경계 (Service Map)
5. 0.211 [PROPOSAL] docs/adr/0001-product-master-in-product-service.md — 결정

### Q10 (적중 없음)
- 질문: MFC 재고 원장의 단일 작성자는 누구인가요?
- 정답 근거: docs/adr/0002-, docs/business/domain-map.md
1. 0.234 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 13. 재고와 운송의 경계 [보완]
2. 0.252 [PROPOSAL] docs/business/WMS-business-logic.md — 제1부 — CDC WMS 책임·원장과 MFC 연계 > 0.3 수량 원장 불변식과 분산 대사 규칙 > 0.3.1 각 시스템이 금지해야 할 처리
3. 0.214 [CONFIRMED] docs/business/OMS-business-logic.md — 6. MFC 배정·예약·시간대 확보
4. 0.196 [CONFIRMED] docs/business/TMS-business-logic.md — 10. MFC 도착·하차·인계 > 10.1 하차 절차 [확정]
5. 0.186 [CONFIRMED] docs/business/TMS-business-logic.md — 3. 운영 시간표 > 3.1 역산 근거 [확정]

### Q12 (적중 없음)
- 질문: 주문 결제 사가의 타이머와 재고 예약 만료 규칙은?
- 정답 근거: docs/business/flows/order-checkout-saga.md
1. 0.280 [ADOPTED_DESIGN] docs/business/OMS-business-logic.md — 7. 결제·주문 확정·만료 복구
2. 0.217 [CONFIRMED] docs/business/OMS-business-logic.md — 7. 결제·주문 확정·만료 복구
3. 0.218 [ADOPTED_DESIGN] docs/business/glossary.md — 3. 주문·결제
4. 0.244 [PROPOSAL] docs/business/OMS-business-logic.md — 부록 A. OMS 하위 시스템 상세 및 시스템 연계 계약 > A.1 OMS 내부 업무 모듈의 구조와 책임 > A.1.4 주문 확정 조정 모듈
5. 0.227 [ADOPTED_DESIGN] docs/business/Commerce-business-logic.md — 4. 회원·인증·동의·계정 관리 > 4.4 탈퇴·보존·재가입

### Q13 (적중 없음)
- 질문: 반품 교환 환불 사가는 어떻게 진행되나요?
- 정답 근거: docs/business/flows/return-exchange-refund.md
1. 0.278 [PROPOSAL] docs/business/OMS-business-logic.md — 부록 A. OMS 하위 시스템 상세 및 시스템 연계 계약 > A.1 OMS 내부 업무 모듈의 구조와 책임 > A.1.12 교환·반품·회수 관리 모듈
2. 0.238 [PROPOSAL] docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회
3. 0.223 [PROPOSAL] docs/business/OMS-business-logic.md — 3. 주문·자원·실거래의 상태 전이와 종결
4. 0.187 [CONFIRMED] docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회 > 13.1 취소·인계 결과가 확인 중일 때의 안내
5. 0.216 [PROPOSAL] docs/business/OMS-business-logic.md — 11. 반품·교환·회수 > 11.1 접수와 자격

### Q14 (적중 없음)
- 질문: CDC 출고 간선 운송 MFC 수령 Transit 대사 흐름은?
- 정답 근거: docs/business/flows/replenishment-transfer.md
1. 0.414 [PROPOSAL] docs/adr/0002-inventory-single-writer-per-center-type.md — 결정 > 5
2. 0.401 [PROPOSAL] docs/business/WMS-business-logic.md — 제4부 — CDC WMS 중심 도메인 연계 계약 및 검증 기준 > 31. 이전 기준과의 충돌·범위 제외 대장
3. 0.398 [ASSUMPTION] docs/business/BackOffice-business-logic.md — BO-28. 통합 현황·업무 지표·조회 재구축 > 3) 원수령과 Transit의 시차 사례 [가정]
4. 0.360 [ADOPTED_DESIGN] docs/business/overview.md — 2. 두 개의 물류 흐름
5. 0.368 [PROPOSAL] docs/business/WMS-business-logic.md — 제2부 — CDC WMS 상세 업무 기능 35개 (원문 기반·권한 재귀속) > 14. 운송 결과·MFC 원수령·수량 대사 > CDC-F-024 MFC 실제 입고 확정 결과 인수·Transit 대사·복구

### Q15 (적중 없음)
- 질문: 시각은 어떤 기준으로 저장하고 영업일은 어떻게 판정하나요?
- 정답 근거: docs/adr/0010-
1. 0.193 [ADOPTED_DESIGN] docs/business/OMS-business-logic.md — 11. 반품·교환·회수 > 11.1 접수와 자격
2. 0.196 [PROPOSAL] docs/business/WMS-business-logic.md — 제3부 — CDC WMS의 5대 식별자 상세 정책과 센터 간 보존계약 > 부록 4. LOT·FEFO·회수 추적 정책 — V6.6 원문 세부 정책 > 정책 16.12 소비기한 및 FEFO 정책과의 관계
3. 0.194 [PROPOSAL] docs/business/WMS-business-logic.md — 제2부 — CDC WMS 상세 업무 기능 35개 (원문 기반·권한 재귀속) > 8. 재고·예약·기한 기준 > CDC-F-009 LOT·소비기한·FEFO 적격 판단
4. 0.184 [ADOPTED_DESIGN] docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회 > 13.1 취소·인계 결과가 확인 중일 때의 안내
