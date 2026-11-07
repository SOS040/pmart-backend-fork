-- 집품 뒤 취소(IV-08, 2026-11-08, ADR-0046).
-- 피킹이 시작된 주문의 작업 중지: 남은 작업 취소·포장 무효화·출고 취소 → 집품·포장분은 inventory 취소 복귀 대기(판매 제외)로 →
-- 복귀 작업(RETURN: 포장 위치 → 선반)이 끝나면 다시 판매 가능.

ALTER TABLE task DROP CONSTRAINT ck_task_type;
ALTER TABLE task ADD CONSTRAINT ck_task_type CHECK (task_type IN ('PICK', 'PACK', 'DISPATCH', 'RETURN'));

-- RETURN 작업의 복귀 대상 [{orderLineId, sku, lotId, locationCode, workState, qty}] (작업 행은 주문 줄 합)
ALTER TABLE task ADD COLUMN returns jsonb;

-- 중지 판정 세부. cancel_return = inventory cancel-returns 요청 본문(null 이면 아직 준비 전 — 진행 중 실적이 끝나길 기다림),
-- package_verdicts = 포장·출고별 판정 [{packageId?, shipmentId, verdict, stoppedLines[{orderLineId, qty, workStateAtStop}]}],
-- last_error = 재고 거절 사유(운영자 확인).
ALTER TABLE order_work_stop
    ADD COLUMN cancel_return    jsonb,
    ADD COLUMN package_verdicts jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN last_error       varchar(300),
    ADD COLUMN resolved_at      timestamptz;
CREATE INDEX ix_order_work_stop_pending ON order_work_stop (decided_at) WHERE state = 'PENDING';
