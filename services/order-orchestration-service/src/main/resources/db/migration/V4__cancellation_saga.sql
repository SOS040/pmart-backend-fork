-- S3 취소 사가(2026-10-31, 슬라이스 8 첫 단계: 피킹 전 전체 취소). 사가 진행 사본이며 중지·재고·주문 수량의 정본은 각 서비스다.
-- 단계: STOP_WORK(mfc-fulfillment 판정) → RELEASE_RESOURCES(S1 에서 확정한 재고·여력·슬롯 해제) → APPLY_ORDER_CANCEL(order 수량 반영).
-- 환불은 claim-service 가 OrderCancelled 를 받아 실행한다(flows/cancel-vs-handover.md §2 e).
CREATE TABLE cancellation_saga (
    saga_id            VARCHAR(64)  PRIMARY KEY,
    claim_id           VARCHAR(64)  NOT NULL,
    stop_request_id    VARCHAR(128) NOT NULL,
    order_id           VARCHAR(64)  NOT NULL,
    scope              VARCHAR(8)   NOT NULL,
    reason             VARCHAR(32),
    lines              JSONB        NOT NULL,      -- [{orderLineId, qty}]
    state              VARCHAR(32)  NOT NULL,
    current_step       VARCHAR(32)  NOT NULL,
    version            INT          NOT NULL DEFAULT 0,
    stop_result        JSONB,                      -- mfc-fulfillment 판정 사본
    resources          JSONB,                      -- [{resourceType, resourceId, state}] 해제 대상·확인 상태
    last_error_step    VARCHAR(32),
    last_error_code    VARCHAR(64),
    last_error_message VARCHAR(500),
    started_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ,
    CONSTRAINT uq_cancellation_saga_stop UNIQUE (stop_request_id)
);
CREATE INDEX ix_cancellation_saga_order ON cancellation_saga (order_id);
CREATE INDEX ix_cancellation_saga_open ON cancellation_saga (state, updated_at)
    WHERE state IN ('RUNNING', 'WAITING_RESULT');
