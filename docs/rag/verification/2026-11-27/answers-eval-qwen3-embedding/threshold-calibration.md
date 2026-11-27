# 근거 없음 임계값 맞추기 (ollama:qwen3-embedding:0.6b@eval/1024)

- 혼합 유사도 = 코사인 0.60 + 키워드 0.40. 지금 임계값 0.18
- 답이 있는 질문의 최저 1위 점수 **0.444**, 답이 없는 질문의 최고 1위 점수 **0.451** → 겹침(임계값 하나로 다 나눌 수 없음)

| 후보 임계값 | 답 있는 질문 중 근거 없음으로 잘못 감 | 답 없는 질문 중 걸러짐 |
|---|---|---|
| 0.300 | 0/19 | 1/14 |
| 0.325 | 0/19 | 1/14 |
| 0.350 | 0/19 | 3/14 |
| 0.375 | 0/19 | 7/14 |
| 0.400 | 0/19 | 11/14 |
| 0.425 | 0/19 | 12/14 |
| 0.450 | 2/19 | 13/14 |
| 0.475 | 2/19 | 14/14 |
| 0.500 | 4/19 | 14/14 |
| 0.525 | 5/19 | 14/14 |
| 0.550 | 9/19 | 14/14 |
| 0.575 | 11/19 | 14/14 |
| 0.600 | 14/19 | 14/14 |
| 0.625 | 17/19 | 14/14 |
| 0.650 | 17/19 | 14/14 |
| 0.675 | 19/19 | 14/14 |
| 0.700 | 19/19 | 14/14 |

| 순위 | id | 기대 | 1위 점수 | 1위 문서 | 질문 |
|---|---|---|---|---|---|
| 1 | A01 | ANSWER | 0.675 | docs/rag/rag-docs/order_claim/01-order-amount.md | 봉투값은 주문당 얼마이고 언제 환불되나요? |
| 2 | B04 | ANSWER | 0.651 | docs/rag/rag-docs/backoffice/03-approval.md | 내가 요청한 변경을 내가 승인할 수 있나요? |
| 3 | A18 | ANSWER | 0.623 | docs/rag/rag-docs/order_claim/01-order-amount.md | 첫 구매 고객의 배달팁은 어떻게 되나요? |
| 4 | A02 | ANSWER | 0.622 | docs/rag/rag-docs/common/02-business-overview.md | 결제 후에 배송 주소나 연락처를 바꿀 수 있나요? |
| 5 | B06 | ANSWER | 0.606 | docs/rag/rag-docs/order_claim/01-order-amount.md | 주문서를 만든 뒤 가격은 얼마 동안 유지되나요? |
| 6 | A06 | ANSWER | 0.595 | docs/rag/rag-docs/order_claim/01-order-amount.md | 주류 주문의 성인 인증은 어떻게 하나요? |
| 7 | B05 | ANSWER | 0.589 | docs/rag/rag-docs/cdc_wms/02-lot-shelf-life.md | CDC 에서 소비기한 잔여일수는 어떤 날짜 기준으로 계산하나요? |
| 8 | A03 | ANSWER | 0.588 | docs/rag/rag-docs/commerce/01-product-sku.md | 같은 기초상품의 구매 제한 기본 상한은 몇 개인가요? |
| 9 | B03 | ANSWER | 0.567 | docs/rag/rag-docs/backoffice/08-hr-employee-management.md | 직원 사번은 어떤 형식인가요? |
| 10 | A04 | ANSWER | 0.565 | docs/rag/rag-docs/rider_app/03-pickup-delivery.md | 배달 완료를 시간이 지나면 자동으로 완료 처리하나요? |
| 11 | B07 | ANSWER | 0.545 | docs/rag/rag-docs/rider_app/02-operation-dispatch.md | 라이더 배차 방식에는 어떤 것이 있나요? |
| 12 | B01 | ANSWER | 0.541 | docs/rag/rag-docs/delivery/07-earnings-operations.md | 라이더 수행료는 언제 지급되고, 영업일은 어떻게 세나요? |
| 13 | A17 | ANSWER | 0.540 | docs/rag/rag-docs/order_claim/01-order-amount.md | 최소 주문금액은 얼마이고 무엇을 기준으로 판정하나요? |
| 14 | A13 | ANSWER | 0.527 | docs/rag/rag-docs/mfc/04-picking-packing.md | 피킹 중에 결품이 나면 주문은 어떻게 처리되나요? |
| 15 | A05 | ANSWER | 0.521 | docs/rag/rag-docs/delivery/01-service-area.md | 분할 배송 기준 무게는 얼마인가요? |
| 16 | A19 | ANSWER | 0.494 | docs/rag/rag-docs/tms/02-dispatch-planning.md | MFC 피킹 방문 순서는 온도대별로 어떻게 정해지나요? |
| 17 | A15 | ANSWER | 0.493 | docs/rag/rag-docs/cdc_wms/03-replenishment-transfer-departure.md | 보충 물량은 어느 CDC에서 몇 곳의 MFC로 운송하나요? |
| 18 | N01 | NONE | 0.451 | docs/rag/rag-docs/cdc_wms/02-lot-shelf-life.md | 연차는 1년에 며칠 쓸 수 있나요? |
| 19 | A14 | ANSWER | 0.448 | docs/rag/rag-docs/mfc/02-task-assignment.md | MFC 작업 배정 추천의 최적화 목표 우선순위는 어떻게 되나요? |
| 20 | N07 | NONE | 0.445 | docs/rag/rag-docs/delivery/07-earnings-operations.md | 라이더 유니폼과 헬멧은 어떤 기준으로 지급하나요? |
| 21 | B02 | ANSWER | 0.444 | docs/rag/rag-docs/backoffice/03-approval.md | MFC 재고 정정이나 폐기는 언제 본사 승인이 필요한가요? |
| 22 | N04 | NONE | 0.417 | docs/rag/rag-docs/backoffice/01-staff-account-login.md | 사무실 와이파이 비밀번호가 뭐예요? |
| 23 | A16 | NONE | 0.400 | docs/rag/rag-docs/commerce/04-search.md | 고객 분류 카테고리는 몇 단계로 구성하나요? |
| 24 | N06 | NONE | 0.387 | docs/rag/rag-docs/tms/05-completion-settlement.md | 출장비 정산 기준은 어떻게 되나요? |
| 25 | N08 | NONE | 0.382 | docs/rag/rag-docs/common/01-glossary.md | MFC 냉장고가 고장 나면 어느 수리업체에 연락하나요? |
| 26 | A20 | NONE | 0.376 | docs/rag/rag-docs/mfc/01-worker-qualification-shift.md | 직원 휴가 신청은 어떻게 하나요? |
| 27 | A08 | NONE | 0.368 | docs/rag/rag-docs/common/01-glossary.md | 주문 사가는 오케스트레이션인가요 코레오그래피인가요? |
| 28 | N03 | NONE | 0.365 | docs/rag/rag-docs/rider_app/01-signup.md | 사내 주차 등록은 어디서 신청하나요? |
| 29 | N02 | NONE | 0.363 | docs/rag/rag-docs/customer_app/13-return-exchange.md | 회식비는 어떻게 정산하나요? |
| 30 | A10 | NONE | 0.350 | docs/rag/rag-docs/backoffice/06-product-display-search.md | 컨테이너 이미지는 어느 레지스트리에 올리나요? |
| 31 | A12 | NONE | 0.337 | docs/rag/rag-docs/customer_app/04-consents-privacy.md | 서비스 간 내부 호출에도 인증 토큰이 필요한가요? |
| 32 | A11 | NONE | 0.328 | docs/rag/rag-docs/customer_app/01-signup-login.md | 서비스 설정의 정본은 어디에 두나요? |
| 33 | N05 | NONE | 0.290 | docs/rag/rag-docs/mfc/05-dispatch-handover.md | 건강검진은 언제 받나요? |
