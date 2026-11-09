# 근거 없음 임계값 맞추기 (ollama:qwen3-embedding:0.6b@eval/1024)

- 혼합 유사도 = 코사인 0.60 + 키워드 0.40. 지금 임계값 0.18
- 답이 있는 질문의 최저 1위 점수 **0.426**, 답이 없는 질문의 최고 1위 점수 **0.407** → 나뉨(사이 값이 후보)

| 후보 임계값 | 답 있는 질문 중 근거 없음으로 잘못 감 | 답 없는 질문 중 걸러짐 |
|---|---|---|
| 0.300 | 0/19 | 1/9 |
| 0.325 | 0/19 | 1/9 |
| 0.350 | 0/19 | 3/9 |
| 0.375 | 0/19 | 5/9 |
| 0.400 | 0/19 | 7/9 |
| 0.425 | 0/19 | 9/9 |
| 0.450 | 1/19 | 9/9 |
| 0.475 | 4/19 | 9/9 |
| 0.500 | 6/19 | 9/9 |
| 0.525 | 9/19 | 9/9 |
| 0.550 | 11/19 | 9/9 |
| 0.575 | 15/19 | 9/9 |
| 0.600 | 17/19 | 9/9 |
| 0.625 | 17/19 | 9/9 |
| 0.650 | 19/19 | 9/9 |
| 0.675 | 19/19 | 9/9 |
| 0.700 | 19/19 | 9/9 |

| 순위 | id | 기대 | 1위 점수 | 1위 문서 | 질문 |
|---|---|---|---|---|---|
| 1 | A08 | ANSWER | 0.639 | docs/adr/0003-order-saga-orchestration.md | 주문 사가는 오케스트레이션인가요 코레오그래피인가요? |
| 2 | A18 | ANSWER | 0.627 | docs/business/OMS-business-logic.md | 첫 구매 고객의 배달팁은 어떻게 되나요? |
| 3 | A19 | ANSWER | 0.584 | docs/adr/0050-or-tools-assignment-routing-no-jgrapht.md | MFC 피킹 방문 순서는 온도대별로 어떻게 정해지나요? |
| 4 | A05 | ANSWER | 0.581 | docs/business/OMS-business-logic.md | 분할 배송 기준 무게는 얼마인가요? |
| 5 | A01 | ANSWER | 0.564 | docs/business/OMS-business-logic.md | 봉투값은 주문당 얼마이고 언제 환불되나요? |
| 6 | A12 | ANSWER | 0.563 | docs/adr/0026-no-service-to-service-authentication.md | 서비스 간 내부 호출에도 인증 토큰이 필요한가요? |
| 7 | A02 | ANSWER | 0.550 | docs/business/OMS-business-logic.md | 결제 후에 배송 주소나 연락처를 바꿀 수 있나요? |
| 8 | A03 | ANSWER | 0.550 | docs/business/Commerce-business-logic.md | 같은 기초상품의 구매 제한 기본 상한은 몇 개인가요? |
| 9 | A07 | ANSWER | 0.547 | docs/adr/0002-inventory-single-writer-per-center-type.md | MFC 재고 수량은 어느 서비스만 변경할 수 있나요? |
| 10 | A13 | ANSWER | 0.537 | docs/adr/0049-shortage-always-partial-cancel.md | 피킹 중에 결품이 나면 주문은 어떻게 처리되나요? |
| 11 | A10 | ANSWER | 0.524 | docs/adr/0012-image-registry-docker-hub-public.md | 컨테이너 이미지는 어느 레지스트리에 올리나요? |
| 12 | A15 | ANSWER | 0.523 | docs/business/overview.md | 보충 물량은 어느 CDC에서 몇 곳의 MFC로 운송하나요? |
| 13 | A14 | ANSWER | 0.510 | docs/adr/0043-slice-6b-picking-packing.md | MFC 작업 배정 추천의 최적화 목표 우선순위는 어떻게 되나요? |
| 14 | A16 | ANSWER | 0.495 | docs/business/Commerce-business-logic.md | 고객 분류 카테고리는 몇 단계로 구성하나요? |
| 15 | A11 | ANSWER | 0.488 | docs/adr/0004-backoffice-ledgers-approval-case-center-master.md | 서비스 설정의 정본은 어디에 두나요? |
| 16 | A06 | ANSWER | 0.474 | docs/business/OMS-business-logic.md | 주류 주문의 성인 인증은 어떻게 하나요? |
| 17 | A17 | ANSWER | 0.471 | docs/business/Commerce-business-logic.md | 최소 주문금액은 얼마이고 무엇을 기준으로 판정하나요? |
| 18 | A09 | ANSWER | 0.464 | docs/business/TMS-business-logic.md | 시각은 어떤 기준으로 저장하고 영업일은 어떻게 판정하나요? |
| 19 | A04 | ANSWER | 0.426 | docs/adr/0006-handover-fence-and-cancel-arbitration.md | 배달 완료를 시간이 지나면 자동으로 완료 처리하나요? |
| 20 | N08 | NONE | 0.407 | docs/business/MFC-business-logic.md | MFC 냉장고가 고장 나면 어느 수리업체에 연락하나요? |
| 21 | N01 | NONE | 0.403 | docs/adr/0010-time-utc-storage-seoul-business-dates.md | 연차는 1년에 며칠 쓸 수 있나요? |
| 22 | N06 | NONE | 0.384 | docs/business/TMS-business-logic.md | 출장비 정산 기준은 어떻게 되나요? |
| 23 | N07 | NONE | 0.382 | docs/architecture/service-map.md | 라이더 유니폼과 헬멧은 어떤 기준으로 지급하나요? |
| 24 | N04 | NONE | 0.368 | docs/adr/0027-config-server-github-cipher.md | 사무실 와이파이 비밀번호가 뭐예요? |
| 25 | N03 | NONE | 0.356 | docs/business/TMS-business-logic.md | 사내 주차 등록은 어디서 신청하나요? |
| 26 | N02 | NONE | 0.348 | docs/business/TMS-business-logic.md | 회식비는 어떻게 정산하나요? |
| 27 | A20 | NONE | 0.332 | docs/business/BackOffice-business-logic.md | 직원 휴가 신청은 어떻게 하나요? |
| 28 | N05 | NONE | 0.253 | docs/adr/0040-mfc-inventory-model-v2.md | 건강검진은 언제 받나요? |
