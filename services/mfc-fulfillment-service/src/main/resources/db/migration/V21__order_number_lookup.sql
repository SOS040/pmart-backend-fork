-- 센터 관리자 지난 주문 조회(2026-11-20, 계약 0.27.0 — MFC 시뮬레이터 R-29): 화면용 주문번호로 찾기
-- (GET /work-orders?orderNumber=, GET /shipments?orderNumber=). 번호는 전체에서 겹치지 않지만(order-service 유일 색인) 사본이라 유일 제약은 두지 않는다.
CREATE INDEX ix_order_display_number_number ON order_display_number (order_number);
