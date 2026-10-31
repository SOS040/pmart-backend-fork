-- 주문 단위 작업 중지(S3-a, 2026-10-31). orchestration 이 취소 접수 뒤 주문 ID 로 중지를 요청한다.
-- 작업 지시(work order)가 아직 없는 주문은 요청 수량 전부 STOPPED(not_started_lines)로 확정하고 기록을 남긴다.
-- 이 기록은 이후 같은 주문의 작업 요청(ConfirmOrder, 슬라이스 6)이 중지된 수량을 빼고 받는 근거다.
CREATE TABLE order_work_stop (
    stop_request_id   varchar(128) NOT NULL,
    order_id          varchar(64)  NOT NULL,
    claim_id          varchar(64),
    scope             varchar(8)   NOT NULL,       -- FULL / PARTIAL
    requested_lines   jsonb        NOT NULL,       -- [{orderLineId, qty}]
    state             varchar(12)  NOT NULL,       -- RESOLVED (작업 지시가 생기면 PENDING 이 생긴다)
    not_started_lines jsonb        NOT NULL,       -- 작업 지시 전 중지된 수량 [{orderLineId, qty}]
    decided_at        timestamptz  NOT NULL,
    CONSTRAINT pk_order_work_stop PRIMARY KEY (stop_request_id)
);
CREATE INDEX ix_order_work_stop_order ON order_work_stop (order_id);
