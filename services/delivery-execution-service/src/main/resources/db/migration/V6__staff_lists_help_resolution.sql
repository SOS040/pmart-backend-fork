-- 백오피스 고객센터·라이더 운영 직원 목록·도움요청 처리함 닫기(2026-11-15, 계약 0.4.0 — 요청서 delivery-backoffice-staff-apis.md BOD-4·5).
-- 지원센터 사건함(case R-4)이 생기기 전까지 도움요청 처리 상태를 이 서비스가 갖는다. 메모에는 개인정보를 적지 않는다.
ALTER TABLE help_request ADD COLUMN resolved_at     TIMESTAMPTZ;
ALTER TABLE help_request ADD COLUMN resolved_by     VARCHAR(128);
ALTER TABLE help_request ADD COLUMN resolution_note VARCHAR(300);

-- 대기 목록(상태별 오래된 것 먼저)
CREATE INDEX help_request_state_created ON help_request (state, created_at);
-- 직원 배송 찾기: 주문 ID, 상태(전달 실패 대기 등)
CREATE INDEX execution_delivery_order ON execution_delivery (order_id);
CREATE INDEX execution_delivery_state_created ON execution_delivery (state, created_at);
