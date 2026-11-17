-- 고객 주문 내역 숨기기(2026-11-18, 계약 0.12.0 — 고객 앱 "주문 내역 삭제" = 숨김, 사용자 "A해줘").
-- 주문을 지우지 않고 본인 목록(GET /orders)에서만 뺀다. 상세 조회·클레임·환불·직원 검색은 그대로. 되돌리기 없음.
-- 끝난 주문(COMPLETED·CANCELLED·EXPIRED)만 숨길 수 있다(OrderHistoryRules.hideable). 상태·버전과 무관한 표시용 값이라 version 을 올리지 않는다.
ALTER TABLE customer_order ADD COLUMN hidden_at timestamptz;
