-- 결제 진입(checkouts, 2026-10-31 자원 확보 단계). 초안마다 마지막 결제 진입의 주문 ID·사가를 기억한다.
-- 진행 상태의 정본은 order-orchestration 사가(조회로 반영) — 여기에는 참조만 둔다.
ALTER TABLE order_draft
    ADD COLUMN order_id            varchar(64),
    ADD COLUMN saga_id             varchar(64),
    ADD COLUMN checkout_request_id varchar(160),
    ADD COLUMN checkout_at         timestamptz;
