# 근거 없음 임계값 맞추기 (local-hash-ngram@1/384)

- 혼합 유사도 = 코사인 0.60 + 키워드 0.40. 지금 임계값 0.18
- 답이 있는 질문의 최저 1위 점수 **0.173**, 답이 없는 질문의 최고 1위 점수 **0.295** → 겹침(임계값 하나로 다 나눌 수 없음)

| 후보 임계값 | 답 있는 질문 중 근거 없음으로 잘못 감 | 답 없는 질문 중 걸러짐 |
|---|---|---|
| 0.300 | 12/19 | 14/14 |
| 0.325 | 13/19 | 14/14 |
| 0.350 | 14/19 | 14/14 |
| 0.375 | 14/19 | 14/14 |
| 0.400 | 17/19 | 14/14 |
| 0.425 | 18/19 | 14/14 |
| 0.450 | 19/19 | 14/14 |
| 0.475 | 19/19 | 14/14 |
| 0.500 | 19/19 | 14/14 |
| 0.525 | 19/19 | 14/14 |
| 0.550 | 19/19 | 14/14 |
| 0.575 | 19/19 | 14/14 |
| 0.600 | 19/19 | 14/14 |
| 0.625 | 19/19 | 14/14 |
| 0.650 | 19/19 | 14/14 |
| 0.675 | 19/19 | 14/14 |
| 0.700 | 19/19 | 14/14 |

| 순위 | id | 기대 | 1위 점수 | 1위 문서 | 질문 |
|---|---|---|---|---|---|
| 1 | B04 | ANSWER | 0.434 | docs/rag/rag-docs/backoffice/03-approval.md | 내가 요청한 변경을 내가 승인할 수 있나요? |
| 2 | A04 | ANSWER | 0.404 | docs/rag/rag-docs/rider_app/03-pickup-delivery.md | 배달 완료를 시간이 지나면 자동으로 완료 처리하나요? |
| 3 | A17 | ANSWER | 0.392 | docs/rag/rag-docs/customer_app/06-order-payment-fees.md | 최소 주문금액은 얼마이고 무엇을 기준으로 판정하나요? |
| 4 | B06 | ANSWER | 0.377 | docs/rag/rag-docs/common/02-business-overview.md | 주문서를 만든 뒤 가격은 얼마 동안 유지되나요? |
| 5 | B05 | ANSWER | 0.377 | docs/rag/rag-docs/cdc_wms/02-lot-shelf-life.md | CDC 에서 소비기한 잔여일수는 어떤 날짜 기준으로 계산하나요? |
| 6 | A06 | ANSWER | 0.329 | docs/rag/rag-docs/order_claim/01-order-amount.md | 주류 주문의 성인 인증은 어떻게 하나요? |
| 7 | B07 | ANSWER | 0.310 | docs/rag/rag-docs/delivery/03-dispatch.md | 라이더 배차 방식에는 어떤 것이 있나요? |
| 8 | A01 | ANSWER | 0.299 | docs/rag/rag-docs/order_claim/01-order-amount.md | 봉투값은 주문당 얼마이고 언제 환불되나요? |
| 9 | A02 | ANSWER | 0.298 | docs/rag/rag-docs/order_claim/02-payment-order-status.md | 결제 후에 배송 주소나 연락처를 바꿀 수 있나요? |
| 10 | N01 | NONE | 0.295 | docs/rag/rag-docs/commerce/07-cart.md | 연차는 1년에 며칠 쓸 수 있나요? |
| 11 | B02 | ANSWER | 0.278 | docs/rag/rag-docs/mfc/06-inventory-quality.md | MFC 재고 정정이나 폐기는 언제 본사 승인이 필요한가요? |
| 12 | B01 | ANSWER | 0.275 | docs/rag/rag-docs/order_claim/07-misdelivery.md | 라이더 수행료는 언제 지급되고, 영업일은 어떻게 세나요? |
| 13 | N07 | NONE | 0.273 | docs/rag/rag-docs/delivery/07-earnings-operations.md | 라이더 유니폼과 헬멧은 어떤 기준으로 지급하나요? |
| 14 | A03 | ANSWER | 0.259 | docs/rag/rag-docs/commerce/01-product-sku.md | 같은 기초상품의 구매 제한 기본 상한은 몇 개인가요? |
| 15 | A05 | ANSWER | 0.256 | docs/rag/rag-docs/delivery/01-service-area.md | 분할 배송 기준 무게는 얼마인가요? |
| 16 | A15 | ANSWER | 0.254 | docs/rag/rag-docs/common/01-glossary.md | 보충 물량은 어느 CDC에서 몇 곳의 MFC로 운송하나요? |
| 17 | A18 | ANSWER | 0.248 | docs/rag/rag-docs/common/02-business-overview.md | 첫 구매 고객의 배달팁은 어떻게 되나요? |
| 18 | A14 | ANSWER | 0.224 | docs/rag/rag-docs/mfc/02-task-assignment.md | MFC 작업 배정 추천의 최적화 목표 우선순위는 어떻게 되나요? |
| 19 | A19 | ANSWER | 0.217 | docs/rag/rag-docs/tms/02-dispatch-planning.md | MFC 피킹 방문 순서는 온도대별로 어떻게 정해지나요? |
| 20 | B03 | ANSWER | 0.216 | docs/rag/rag-docs/backoffice/08-hr-employee-management.md | 직원 사번은 어떤 형식인가요? |
| 21 | N06 | NONE | 0.216 | docs/rag/rag-docs/common/02-business-overview.md | 출장비 정산 기준은 어떻게 되나요? |
| 22 | A10 | NONE | 0.203 | docs/rag/rag-docs/backoffice/06-product-display-search.md | 컨테이너 이미지는 어느 레지스트리에 올리나요? |
| 23 | N04 | NONE | 0.198 | docs/rag/rag-docs/customer_app/07-delivery-area-address.md | 사무실 와이파이 비밀번호가 뭐예요? |
| 24 | A11 | NONE | 0.194 | docs/rag/rag-docs/delivery/01-service-area.md | 서비스 설정의 정본은 어디에 두나요? |
| 25 | N02 | NONE | 0.183 | docs/rag/rag-docs/backoffice/05-customer-center-cases.md | 회식비는 어떻게 정산하나요? |
| 26 | A20 | NONE | 0.180 | docs/rag/rag-docs/mfc/05-dispatch-handover.md | 직원 휴가 신청은 어떻게 하나요? |
| 27 | A16 | NONE | 0.175 | docs/rag/rag-docs/commerce/04-search.md | 고객 분류 카테고리는 몇 단계로 구성하나요? |
| 28 | A12 | NONE | 0.173 | docs/rag/rag-docs/customer_app/05-customer-center.md | 서비스 간 내부 호출에도 인증 토큰이 필요한가요? |
| 29 | A13 | ANSWER | 0.173 | docs/rag/rag-docs/delivery/08-rider-notifications.md | 피킹 중에 결품이 나면 주문은 어떻게 처리되나요? |
| 30 | N08 | NONE | 0.169 | docs/rag/rag-docs/common/02-business-overview.md | MFC 냉장고가 고장 나면 어느 수리업체에 연락하나요? |
| 31 | N03 | NONE | 0.148 | docs/rag/rag-docs/mfc/01-worker-qualification-shift.md | 사내 주차 등록은 어디서 신청하나요? |
| 32 | N05 | NONE | 0.146 | docs/rag/rag-docs/backoffice/08-hr-employee-management.md | 건강검진은 언제 받나요? |
| 33 | A08 | NONE | 0.138 | docs/rag/rag-docs/delivery/05-incidents-help-recovery.md | 주문 사가는 오케스트레이션인가요 코레오그래피인가요? |
