# ADR-0049 결품은 고객 사전 선택과 관계없이 무조건 부분 취소·환불

- 상태: 승인 (2026-11-08, 사용자 "무조건 부분취소환불임")
- 대체: ADR-0045 결정 2·4 의 "고객 사전 선택별 처리"(CANCEL_ALL → 전체 취소, 선택 없음 → 상신), ADR-0046 의 "전체 취소 선택" 경로. 업무 원문 OMS 8.2(사전 선택 `품절 상품만 취소`/`전체 취소`, 선택 없으면 CS 이관)를 사용자가 바꿨다.
- 관련: 사용자 결정 #5 안 C(예약 보류분도 피킹 시작 때 결품으로), `docs/business/flows/order-checkout-saga.md` §2.1

## 결정
1. 결품(`LineShortageReported` — 피킹 중 결품·파손, 피킹 시작 때 남은 예약 보류분)은 **언제나 그 품목만 부분 취소·환불**한다: CHECK_ORDER → RESOLVE_WORK → RELEASE_SHORTFALL → APPLY_ORDER_CHANGE → REQUEST_REFUND(원단가 × 수량, 봉투값·배달팁 제외, 남은 주문이 15,000원 미만이어도 유지).
2. 고객의 사전 선택(`outOfStockPreference`)은 사가에 기록만 하고 처리에 쓰지 않는다. `전체 취소` 선택·선택 없음도 같은 처리 — **상신(NO_PREFERENCE) 없음, CS 이관 불필요**.
3. 대체 상품 없음(그대로).
4. **유지한 예외(내 판단 — 사용자 확인 필요 시 바꿈)**: 결품이 주문의 남은 수량 전부면 보낼 상품이 없으므로 전체 취소(claim 시스템 전체 취소, reason OUT_OF_STOCK, 봉투값·배달팁 포함 전액 환불, ADR-0046). 부분 취소로 다 빼면 아무것도 안 받은 고객이 봉투값·배달팁을 내게 된다.

## 결과
- order-orchestration `ShortageRules`: `escalationAtStart` 언제나 진행, `firstStep` 언제나 CHECK_ORDER.
- 남은 일: 주문서의 품절 처리 선택(필수값)은 이제 쓰이지 않는다 — order 계약·고객 앱에서 뺄지는 클라이언트와 함께(지금은 그대로 받는다). case-service 요청 R-1(선택 없음 CS 이관)은 필요 없어짐.
