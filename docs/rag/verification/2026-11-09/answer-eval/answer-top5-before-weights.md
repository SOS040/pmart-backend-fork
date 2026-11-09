# 답변 평가 질문별 상위 근거 (ollama:qwen3-embedding:0.6b@eval/1024, 임계값 0 — 생성 모델에 넘어가는 순서)

### A01 — 봉투값은 주문당 얼마이고 언제 환불되나요?
1. 0.564 [CONFIRMED] docs/business/OMS-business-logic.md — 5. 가격·쿠폰·금액 확정 > 5.2 부대비용과 혜택
2. 0.471 [CONFIRMED] docs/business/OMS-business-logic.md — 5. 가격·쿠폰·금액 확정 > 5.2 부대비용과 혜택
3. 0.476 [CONFIRMED] docs/business/Commerce-business-logic.md — 10. 고객 주문서·견적 동의·OMS 초안 요청/결과 > 10.2 주문서 검증과 금액 표시
4. 0.444 [CONFIRMED] docs/adr/0049-shortage-always-partial-cancel.md — 결정 > 4
5. 0.452 [ADOPTED_DESIGN] docs/business/overview.md — 4. 핵심 확정 정책 (설계가 바꾸지 않는 것)

### A02 — 결제 후에 배송 주소나 연락처를 바꿀 수 있나요?
1. 0.550 [CONFIRMED] docs/business/OMS-business-logic.md — 4. 주문 초안과 접수 검증
2. 0.542 [PROPOSAL] docs/business/Commerce-business-logic.md — 5. 배송지·서비스 권역·PPC 판정 > 5.1 주소록과 서비스 가능성 판정의 책임
3. 0.503 [PROPOSAL] docs/business/BackOffice-business-logic.md — BO-11. 주문·결제·현장·배송 통합 조회와 CS 접수 > 4) 고객정보 변경의 경계
4. 0.506 [PROPOSAL] docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회 > 13.1 취소·인계 결과가 확인 중일 때의 안내
5. 0.456 [ADOPTED_DESIGN] docs/business/overview.md — 4. 핵심 확정 정책 (설계가 바꾸지 않는 것)

### A03 — 같은 기초상품의 구매 제한 기본 상한은 몇 개인가요?
1. 0.550 [CONFIRMED] docs/business/Commerce-business-logic.md — 2. 공통 용어·상품 식별·구매 정책 > 2.7 최종 확정 E/B SKU와 시스템별 적용 검증 [확정 정책]
2. 0.494 [PROPOSAL] docs/business/BackOffice-business-logic.md — BO-03. 상품·SKU·바코드·환산 기준 승인 > 1) 상품 등록에서 반드시 구별할 데이터
3. 0.459 [CONFIRMED] docs/business/OMS-business-logic.md — 1. OMS의 정의·하위 시스템·협업 경계 > 1.3 다른 영역과의 협업
4. 0.494 [PROPOSAL] docs/business/Commerce-business-logic.md — 1. Commerce 정의·업무 범위·협업 경계 > 1.9 장바구니 하위 시스템의 협업
5. 0.475 [PROPOSAL] docs/business/OMS-business-logic.md — 4. 주문 초안과 접수 검증

### A04 — 배달 완료를 시간이 지나면 자동으로 완료 처리하나요?
1. 0.426 [CONFIRMED] docs/adr/0006-handover-fence-and-cancel-arbitration.md — 결정 > 5
2. 0.504 [PROPOSAL] docs/business/Delivery-business-logic.md — 6. 배송 완료 > 6.4 완료 누락·분할배송
3. 0.447 [ADOPTED_DESIGN] contracts/states/delivery-states.md — Delivery (dispatch 생성, execution 수행)
4. 0.497 [PROPOSAL] docs/business/Delivery-business-logic.md — 7. 배달료 > 7.2 수익·정산
5. 0.410 [ADOPTED_DESIGN] docs/architecture/service-map.md — 7. Last-mile Delivery (6) > delivery-execution-service `[1단계]`

### A05 — 분할 배송 기준 무게는 얼마인가요?
1. 0.581 [PROPOSAL] docs/business/OMS-business-logic.md — 9. 분할배송과 배송 결과
2. 0.497 [ADOPTED_DESIGN] docs/business/Commerce-business-logic.md — 12. 주문내역·배송 조회·분할배송 > 12.6 분할배송의 화면·이벤트·주문 집계 불변 조건 [보완 설계]
3. 0.473 [ADOPTED_DESIGN] docs/business/glossary.md — 4. 배송·운송
4. 0.523 [PROPOSAL] docs/business/Delivery-business-logic.md — 3. 배차요청 (패킹앱) > 3.7 추가·분할 배차
5. 0.502 [PROPOSAL] docs/business/Commerce-business-logic.md — 12. 주문내역·배송 조회·분할배송 > 12.3 분할배송의 품목 배분·실패·완료 집계

### A06 — 주류 주문의 성인 인증은 어떻게 하나요?
1. 0.474 [PROPOSAL] docs/business/OMS-business-logic.md — 6. MFC 배정·예약·시간대 확보
2. 0.479 [PROPOSAL] docs/business/Commerce-business-logic.md — 4. 회원·인증·동의·계정 관리 > 4.3 인증 검증과 동의
3. 0.465 [PROPOSAL] docs/business/Delivery-business-logic.md — 6. 배송 완료 > 6.3 주류 배송
4. 0.405 [PROPOSAL] docs/adr/0005-payment-service-and-pg-source-of-truth.md — 결정
5. 0.403 [PROPOSAL] docs/adr/0020-payment-spec-mapping-toss-widget.md — 구현하지 않은 것(명세 §12 미결정, 안전한 기본 동작만)

### A07 — MFC 재고 수량은 어느 서비스만 변경할 수 있나요?
1. 0.547 [PROPOSAL] docs/adr/0002-inventory-single-writer-per-center-type.md — 결정 > 2
2. 0.520 [PROPOSAL] docs/adr/0002-inventory-single-writer-per-center-type.md — 결정 > 3
3. 0.498 [CONFIRMED] docs/business/OMS-business-logic.md — 6. MFC 배정·예약·시간대 확보
4. 0.493 [ADOPTED_DESIGN] docs/architecture/service-map.md — 5. MFC (6) > mfc-inventory-service `[1단계]` — MFC 재고 **단일 쓰기 주체**
5. 0.446 [CONFIRMED] docs/adr/0047-package-handover-fence.md — 결정 > 5

### A08 — 주문 사가는 오케스트레이션인가요 코레오그래피인가요?
1. 0.639 [CONFIRMED] docs/adr/0003-order-saga-orchestration.md — ADR-0003 주문 계열 사가는 orchestration, 사실 전파는 choreography
2. 0.452 [CONFIRMED] docs/adr/0003-order-saga-orchestration.md — 결정
3. 0.382 [CONFIRMED] docs/adr/0003-order-saga-orchestration.md — 배경
4. 0.379 [CONFIRMED] docs/adr/0049-shortage-always-partial-cancel.md — 결과
5. 0.359 [CONFIRMED] docs/adr/0045-shortage-saga-cancel-out-of-stock-only.md — 결정 > 1

### A09 — 시각은 어떤 기준으로 저장하고 영업일은 어떻게 판정하나요?
1. 0.464 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 3. 운영 시간표 > 3.4 시간 저장 규칙 [보완]
2. 0.421 [PROPOSAL] docs/adr/0010-time-utc-storage-seoul-business-dates.md — 결정 > 5
3. 0.350 [CONFIRMED] docs/adr/0041-per-product-shelf-life-guarantee.md — 배경
4. 0.349 [CONFIRMED] docs/adr/0041-per-product-shelf-life-guarantee.md — 결정 > 2. mfc-inventory 배정 규칙 (ADR-0040 결정 5 수정)
5. 0.387 [PROPOSAL] docs/adr/0010-time-utc-storage-seoul-business-dates.md — 결정 > 3

### A10 — 컨테이너 이미지는 어느 레지스트리에 올리나요?
1. 0.524 [PROPOSAL] docs/adr/0012-image-registry-docker-hub-public.md — 결과
2. 0.501 [PROPOSAL] docs/adr/0016-object-storage-seaweedfs-s3.md — ADR-0016 상품 이미지는 S3 호환 저장소에 두고, 로컬·LAN 은 SeaweedFS 로 띄운다
3. 0.492 [PROPOSAL] docs/adr/0012-image-registry-docker-hub-public.md — 대안
4. 0.452 [CONFIRMED] docs/adr/0028-dev-base-image-temurin-jdk.md — ADR-0028 개발 단계 컨테이너 베이스는 eclipse-temurin:21-jdk — 운영은 GraalVM Native 로 따로 정한다
5. 0.467 [PROPOSAL] docs/adr/0012-image-registry-docker-hub-public.md — 결정 > 1

### A11 — 서비스 설정의 정본은 어디에 두나요?
1. 0.488 [PROPOSAL] docs/adr/0004-backoffice-ledgers-approval-case-center-master.md — 결과
2. 0.446 [CONFIRMED] docs/adr/0029-config-repo-as-source-of-truth.md — 결정 > 2
3. 0.438 [CONFIRMED] docs/adr/0029-config-repo-as-source-of-truth.md — 배경
4. 0.418 [CONFIRMED] docs/adr/0029-config-repo-as-source-of-truth.md — 결과
5. 0.416 [CONFIRMED] docs/adr/0029-config-repo-as-source-of-truth.md — 대안

### A12 — 서비스 간 내부 호출에도 인증 토큰이 필요한가요?
1. 0.563 [CONFIRMED] docs/adr/0026-no-service-to-service-authentication.md — ADR-0026 서비스 간 호출은 인증하지 않는다 — 신뢰 경계는 private subnet 과 API Gateway
2. 0.546 [CONFIRMED] docs/adr/0026-no-service-to-service-authentication.md — 배경
3. 0.519 [CONFIRMED] docs/adr/0026-no-service-to-service-authentication.md — 결정 > 1
4. 0.532 [PROPOSAL] docs/adr/0011-service-identity-tokens.md — ADR-0011 서비스 신원 토큰: auth-service 가 client_credentials 로 발급, /internal 호출에만 사용
5. 0.528 [PROPOSAL] docs/adr/0011-service-identity-tokens.md — 결과

### A13 — 피킹 중에 결품이 나면 주문은 어떻게 처리되나요?
1. 0.537 [CONFIRMED] docs/adr/0049-shortage-always-partial-cancel.md — 결정 > 1
2. 0.497 [CONFIRMED] docs/adr/0043-slice-6b-picking-packing.md — 결정 > 7
3. 0.492 [CONFIRMED] docs/adr/0046-cancel-after-pick-return.md — 배경
4. 0.470 [CONFIRMED] docs/adr/0046-cancel-after-pick-return.md — 결정 > 1
5. 0.464 [CONFIRMED] docs/adr/0049-shortage-always-partial-cancel.md — 결정 > 4

### A14 — MFC 작업 배정 추천의 최적화 목표 우선순위는 어떻게 되나요?
1. 0.510 [CONFIRMED] docs/adr/0043-slice-6b-picking-packing.md — 결정 > 1
2. 0.461 [CONFIRMED] docs/adr/0050-or-tools-assignment-routing-no-jgrapht.md — OR-Tools 사용 범위 (이 ADR 기준)
3. 0.500 [PROPOSAL] docs/business/MFC-business-logic.md — 2. 작업관리 > 1. 목적·책임과 주요 용어
4. 0.463 [ADOPTED_DESIGN] docs/architecture/service-map.md — 5. MFC (6) > mfc-capacity-service `[1단계]`
5. 0.481 [PROPOSAL] docs/adr/0008-slot-capacity-ownership.md — 결정 > 2

### A15 — 보충 물량은 어느 CDC에서 몇 곳의 MFC로 운송하나요?
1. 0.523 [ADOPTED_DESIGN] docs/business/overview.md — 1. 한 줄 요약
2. 0.498 [PROPOSAL] docs/business/WMS-business-logic.md — 제1부 — CDC WMS 책임·원장과 MFC 연계 > 0.3 수량 원장 불변식과 분산 대사 규칙
3. 0.481 [PROPOSAL] docs/business/WMS-business-logic.md — 제3부 — CDC WMS의 5대 식별자 상세 정책과 센터 간 보존계약 > 부록 3. LPN/BOX/PLT 화물 구성 정책 — V6.6 원문 세부 정책 > 정책 15.8 CDC → MFC 보충 출고 및 검수
4. 0.478 [PROPOSAL] docs/business/WMS-business-logic.md — 제3부 — CDC WMS의 5대 식별자 상세 정책과 센터 간 보존계약 > 부록 2. LOC 위치 코드·적치 정책 — V6.6 원문 세부 정책 > 정책 14.8 CDC → MFC 보충과 MFC 적치
5. 0.478 [PROPOSAL] docs/business/WMS-business-logic.md — 제2부 — CDC WMS 상세 업무 기능 35개 (원문 기반·권한 재귀속) > 14. 운송 결과·MFC 원수령·수량 대사 > CDC-F-024 MFC 실제 입고 확정 결과 인수·Transit 대사·복구

### A16 — 고객 분류 카테고리는 몇 단계로 구성하나요?
1. 0.495 [CONFIRMED] docs/business/Commerce-business-logic.md — 6. 상품·카테고리·검색·전시 > 6.6 노출과 구매 가능성
2. 0.457 [PROPOSAL] docs/adr/0015-category-tree-follows-reference-data.md — 배경
3. 0.378 [CONFIRMED] docs/adr/0051-rag-customer-expansion-split-services-ollama.md — 결정 > 2
4. 0.358 [CONFIRMED] docs/adr/0051-rag-customer-expansion-split-services-ollama.md — 결정 대기
5. 0.393 [PROPOSAL] docs/adr/0021-search-index-opensearch-projection.md — 결정 > 7

### A17 — 최소 주문금액은 얼마이고 무엇을 기준으로 판정하나요?
1. 0.471 [CONFIRMED] docs/business/Commerce-business-logic.md — 10. 고객 주문서·견적 동의·OMS 초안 요청/결과 > 10.2 주문서 검증과 금액 표시
2. 0.454 [CONFIRMED] docs/business/OMS-business-logic.md — 5. 가격·쿠폰·금액 확정 > 5.2 부대비용과 혜택
3. 0.425 [CONFIRMED] docs/adr/0041-per-product-shelf-life-guarantee.md — 결정 > 2. mfc-inventory 배정 규칙 (ADR-0040 결정 5 수정)
4. 0.488 [PROPOSAL] docs/business/Commerce-business-logic.md — 10. 고객 주문서·견적 동의·OMS 초안 요청/결과 > 10.4 주문서 금액의 표시 사례
5. 0.407 [CONFIRMED] docs/adr/0041-per-product-shelf-life-guarantee.md — 결정 > 1. product-service SKU 필드 `shelfLifeGuarantee`

### A18 — 첫 구매 고객의 배달팁은 어떻게 되나요?
1. 0.627 [PROPOSAL] docs/business/OMS-business-logic.md — 5. 가격·쿠폰·금액 확정 > 5.2 부대비용과 혜택
2. 0.584 [CONFIRMED] docs/business/Commerce-business-logic.md — 8. 고객에게 표시하는 기본가격·배달팁·최종 견적 (OMS 확정액 참조) > 8.8 첫 구매 무료배달의 동시 주문·실패/취소 복원 — 고객앱 표시와 OMS 점유 분리 [중요]
3. 0.552 [CONFIRMED] docs/business/OMS-business-logic.md — 5. 가격·쿠폰·금액 확정 > 5.2 부대비용과 혜택
4. 0.556 [CONFIRMED] docs/business/Commerce-business-logic.md — 10. 고객 주문서·견적 동의·OMS 초안 요청/결과 > 10.2 주문서 검증과 금액 표시
5. 0.531 [CONFIRMED] docs/business/Commerce-business-logic.md — 1. Commerce 정의·업무 범위·협업 경계 > 1.0 1차 구현 범위 — 프로모션·할인·쿠폰 제외

### A19 — MFC 피킹 방문 순서는 온도대별로 어떻게 정해지나요?
1. 0.584 [CONFIRMED] docs/adr/0050-or-tools-assignment-routing-no-jgrapht.md — 결정 > 2
2. 0.481 [CONFIRMED] docs/adr/0050-or-tools-assignment-routing-no-jgrapht.md — 결정 > 5
3. 0.483 [CONFIRMED] docs/business/TMS-business-logic.md — 7. 배차 > 7.4 멀티드롭 [확정]
4. 0.445 [CONFIRMED] docs/business/TMS-business-logic.md — 3. 운영 시간표 > 3.1 역산 근거 [확정]
5. 0.443 [CONFIRMED] docs/business/MFC-business-logic.md — 1. MFC 규모·시설·운영 설계 > 6. 부산 MFC점 주문·매출 추정 — 75,275가구 [확정 입력] > 6-3. 발주·입고 주기 최종 운영 시나리오 [이번 보완: 고정 방문 + 차등 발주]

### A20 — 직원 휴가 신청은 어떻게 하나요?
1. 0.332 [PROPOSAL] docs/business/BackOffice-business-logic.md — BO-01. 운영자 등록·권한 부여·회수 > 1) 권한 요청서의 행 단위
2. 0.330 [ADOPTED_DESIGN] docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회 > 13.5 클레임 대상별 상태 전이·부분 처리·철회
3. 0.318 [ADOPTED_DESIGN] docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회 > 13.6 품목별 클레임 잔량·철회·중복 접수의 안내
4. 0.283 [ADOPTED_DESIGN] docs/architecture/service-map.md — 2. Order & Payment (5) > claim-service `[1단계: 취소·반품 접수 / 2단계: 교환·회수 완성]`
5. 0.289 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 목차

### N01 — 연차는 1년에 며칠 쓸 수 있나요?
1. 0.403 [PROPOSAL] docs/adr/0010-time-utc-storage-seoul-business-dates.md — 결과
2. 0.389 [ADOPTED_DESIGN] docs/business/WMS-business-logic.md — 제2부 — CDC WMS 상세 업무 기능 35개 (원문 기반·권한 재귀속) > 8. 재고·예약·기한 기준 > CDC-F-009 LOT·소비기한·FEFO 적격 판단
3. 0.338 [CONFIRMED] docs/adr/0041-per-product-shelf-life-guarantee.md — 배경
4. 0.331 [CONFIRMED] docs/adr/0034-kafka-event-history-retention-and-reindex.md — 배경
5. 0.328 [CONFIRMED] docs/adr/0040-mfc-inventory-model-v2.md — 결정 > 5. 소비기한 기준 (Q-05 일부)

### N02 — 회식비는 어떻게 정산하나요?
1. 0.348 [CONFIRMED] docs/business/TMS-business-logic.md — 15. 운송료 정산 [실제 + 확정 / 금액은 가정] > 15.1 운임 구조 [확정]
2. 0.363 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 목차
3. 0.350 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 15. 운송료 정산 [실제 + 확정 / 금액은 가정]
4. 0.384 [PROPOSAL] docs/business/Commerce-business-logic.md — 부록 H. Commerce 고객앱 여정·시스템 시퀀스·상태·가격·클레임 Mermaid [보완 시각화] > H.5 기본가격·무료배달·봉투값 계산
5. 0.334 [CONFIRMED] docs/business/OMS-business-logic.md — 5. 가격·쿠폰·금액 확정 > 5.2 부대비용과 혜택

### N03 — 사내 주차 등록은 어디서 신청하나요?
1. 0.356 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 9. 상차·출차·운송 관제 > 9.1 상차 [실제 + 확정]
2. 0.346 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 4. 기준정보 > 4.5 운송사·기사·계약 [실제 + 확정]
3. 0.314 [CONFIRMED] docs/business/TMS-business-logic.md — 9. 상차·출차·운송 관제 > 9.2 출차 [확정]
4. 0.352 [PROPOSAL] docs/business/BackOffice-business-logic.md — BO-01. 운영자 등록·권한 부여·회수 > 1) 권한 요청서의 행 단위
5. 0.332 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 7. 배차 > 7.5 차량·기사 배정 (운송사) [실제 + 확정]

### N04 — 사무실 와이파이 비밀번호가 뭐예요?
1. 0.368 [CONFIRMED] docs/adr/0027-config-server-github-cipher.md — 결정 > 7
2. 0.357 [CONFIRMED] docs/adr/0032-eureka-service-discovery-local-lan.md — 결정 > 7
3. 0.354 [CONFIRMED] docs/adr/0029-config-repo-as-source-of-truth.md — 결정 > 1
4. 0.342 [CONFIRMED] docs/adr/0026-no-service-to-service-authentication.md — 결정 > 5
5. 0.382 [PROPOSAL] docs/adr/0009-outbox-event-envelope-idempotency.md — 결과

### N05 — 건강검진은 언제 받나요?
1. 0.253 [CONFIRMED] docs/adr/0040-mfc-inventory-model-v2.md — 결정 > 8. 입고 흐름 (사용자 결정 2026-11-07)
2. 0.245 [CONFIRMED] docs/adr/0033-config-refresh-webhook-bus.md — 결정 > 3
3. 0.253 [CONFIRMED] docs/business/MFC-business-logic.md — 1. MFC 규모·시설·운영 설계 > 6. 부산 MFC점 주문·매출 추정 — 75,275가구 [확정 입력] > 6-6. 오전 검수 인원 — 입고 3시간 완료 목표 [산출·가정]
4. 0.242 [CONFIRMED] docs/adr/0040-mfc-inventory-model-v2.md — 결정 > 3. 수령·검수·적치 (IV-02·03)
5. 0.286 [PROPOSAL] docs/business/SCM-business-logic.md — 8. 보충 필요량 계산·정책 선택 > SCM-D04 — 시간대별 부족을 판정하고 필요량을 산출하는 처리

### N06 — 출장비 정산 기준은 어떻게 되나요?
1. 0.384 [CONFIRMED] docs/business/TMS-business-logic.md — 15. 운송료 정산 [실제 + 확정 / 금액은 가정] > 15.1 운임 구조 [확정]
2. 0.384 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 21. 상세 처리 절차 (P01~P12) > P12 — 일 운임 계산·마감·정산
3. 0.372 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 15. 운송료 정산 [실제 + 확정 / 금액은 가정]
4. 0.346 [ADOPTED_DESIGN] docs/business/TMS-business-logic.md — 목차
5. 0.321 [CONFIRMED] docs/business/WMS-business-logic.md — 제2부 — CDC WMS 상세 업무 기능 35개 (원문 기반·권한 재귀속) > 13. 가상 분류·검품·상차와 단일 출발 > CDC-F-022 1요청·1이관·1출발 확정·중복 통지 대사

### N07 — 라이더 유니폼과 헬멧은 어떤 기준으로 지급하나요?
1. 0.382 [ADOPTED_DESIGN] docs/architecture/service-map.md — 7. Last-mile Delivery (6) > rider-service `[1단계]`
2. 0.357 [CONFIRMED] docs/adr/0047-package-handover-fence.md — 결정 > 7
3. 0.318 [CONFIRMED] docs/adr/0006-handover-fence-and-cancel-arbitration.md — 결정 > 1
4. 0.316 [CONFIRMED] docs/adr/0050-or-tools-assignment-routing-no-jgrapht.md — 결정 > 1
5. 0.311 [ADOPTED_DESIGN] docs/architecture/service-map.md — 6. Middle-mile TMS (5) > fleet-service `[2단계]`

### N08 — MFC 냉장고가 고장 나면 어느 수리업체에 연락하나요?
1. 0.407 [CONFIRMED] docs/business/MFC-business-logic.md — 1. MFC 규모·시설·운영 설계 > 6. 부산 MFC점 주문·매출 추정 — 75,275가구 [확정 입력] > 6-3. 발주·입고 주기 최종 운영 시나리오 [이번 보완: 고정 방문 + 차등 발주]
2. 0.441 [PROPOSAL] docs/business/MFC-business-logic.md — 1. MFC 규모·시설·운영 설계 > 10. 운영 검증에 필요한 데이터와 현재 채운 계산값
3. 0.408 [ADOPTED_DESIGN] docs/architecture/service-map.md — 5. MFC (6) > mfc-quality-service `[1단계 최소 / 2단계 완성]`
4. 0.431 [PROPOSAL] docs/business/MFC-business-logic.md — 1. MFC 규모·시설·운영 설계 > MFC의 주요 업무
5. 0.380 [CONFIRMED] docs/adr/0040-mfc-inventory-model-v2.md — ADR-0040 MFC 재고 모델 v2 — 위치·LOT·품질·업무상태별 잔량, LOT 단위 예약(FEFO·최소 잔여일), 단계별 검수·적치
