-- 2026-11-08(ADR-0046): "주문 하나에 진행 중인 취소 하나" 는 현장 중지가 있는 전체 취소에만.
-- 결품 부분 환불 클레임(type CANCEL, reason OUT_OF_STOCK, stop_request_id 없음)은 여러 개가 동시에 진행될 수 있고,
-- 그 뒤 결품 사가의 시스템 전체 취소도 받아야 한다.
DROP INDEX ux_claim_active_cancel;
CREATE UNIQUE INDEX ux_claim_active_cancel ON claim (order_id)
    WHERE type = 'CANCEL' AND stop_request_id IS NOT NULL AND state NOT IN ('CLOSED', 'REJECTED', 'WITHDRAWN');
