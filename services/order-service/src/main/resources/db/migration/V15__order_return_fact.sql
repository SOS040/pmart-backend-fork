-- 반품 수량 반영(O-7, 2026-11-17 — claim CL-1 추가 결정 9): claim 반품 종결(ClaimClosed outcome RETURN_REFUNDED·RETURN_SHORT_CLOSED)의
-- returnedLines 를 주문 행 returned_qty 에 더한다. 클레임 하나에 한 번(업무 키 kind RETURN · source_ref claimId) — 이행 사실 기록 표를 같이 쓴다.
ALTER TABLE order_fulfillment_fact DROP CONSTRAINT ck_order_fulfillment_fact_kind;
ALTER TABLE order_fulfillment_fact
    ADD CONSTRAINT ck_order_fulfillment_fact_kind CHECK (kind IN ('HANDOVER', 'DELIVERY', 'RETURN'));
