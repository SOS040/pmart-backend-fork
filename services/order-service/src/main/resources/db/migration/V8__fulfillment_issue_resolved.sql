-- 이행 확인 필요 자동 종결(2026-11-08 #5 안 C): 피킹 시작 전에 MFC 가 보류분을 다시 잡으면(ReservationBlockCleared, 남은 보류 0) RESOLVED.
ALTER TABLE order_fulfillment_issue DROP CONSTRAINT ck_order_fulfillment_issue_state;
ALTER TABLE order_fulfillment_issue ADD CONSTRAINT ck_order_fulfillment_issue_state
    CHECK (state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'));
ALTER TABLE order_fulfillment_issue ADD COLUMN resolved_at timestamptz;
