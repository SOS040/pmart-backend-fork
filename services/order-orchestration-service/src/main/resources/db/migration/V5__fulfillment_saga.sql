-- S2 이행 요청 사가(2026-11-07, 슬라이스 6a). docs/business/flows/order-checkout-saga.md §2·§2.0.
-- 시작: OrderConfirmed(order.order.v1). 단계 REQUEST_FULFILLMENT(mfc-fulfillment ConfirmOrder, 작업 버전마다 시도 1행)
--   → 접수면 COMPLETED(outcome WORK_ACCEPTED)
--   → 재시도 대상 거절(재고 불일치·운영 중단)이면 RETRY_WAITING(AWAIT_RECOVERY): 1분마다 원래 재고 예약 재확인, 처음 거절부터 W분,
--     새 작업 버전 최대 M번(센터 운영 설정 — 없으면 CONFIG_REQUIRED 보류). 배송 슬롯 시작이 지나면 기다리지 않는다.
--   → 그 밖·재시도 끝 → REQUEST_SYSTEM_CANCEL(claim 시스템 자동 전체 취소 → S3) 뒤 COMPLETED(outcome CANCEL_REQUESTED).
--   고객 취소(S3)가 먼저 시작됐으면 그 자리에서 닫는다(outcome CLOSED_BY_CANCEL).
-- 사가 진행 사본이다. 작업 접수·재고·클레임의 정본은 각 서비스. 대기 마감(처음 거절 + W)과 사용한 재시도 수(work_version − 1)는 저장하지 않고 계산한다.
CREATE TABLE fulfillment_saga (
    saga_id            VARCHAR(64)  PRIMARY KEY,
    order_id           VARCHAR(64)  NOT NULL,
    mfc_id             VARCHAR(32)  NOT NULL,
    reservation_id     VARCHAR(64)  NOT NULL,
    slot_start         TIMESTAMPTZ,                 -- 예약 주문 배송 슬롯 시작(없으면 즉시 배송)
    request            JSONB        NOT NULL,       -- ConfirmOrder 본문 사본(workVersion·sagaId 제외, OrderConfirmed 에서 만든 것)
    state              VARCHAR(32)  NOT NULL,
    current_step       VARCHAR(32)  NOT NULL,
    work_version       INT          NOT NULL,       -- 지금(마지막으로) 보낸 작업 버전
    first_rejected_at  TIMESTAMPTZ,                 -- 대기 W분 기준(처음 거절, 한 번만)
    next_check_at      TIMESTAMPTZ,                 -- RETRY_WAITING 다음 재확인 시각
    outcome            VARCHAR(24),                 -- WORK_ACCEPTED / CANCEL_REQUESTED / CLOSED_BY_CANCEL
    fulfillment_id     VARCHAR(64),
    claim_id           VARCHAR(64),
    version            INT          NOT NULL DEFAULT 0,
    last_error_step    VARCHAR(32),
    last_error_code    VARCHAR(64),
    last_error_message VARCHAR(500),
    started_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ,
    CONSTRAINT uq_fulfillment_saga_order UNIQUE (order_id),
    CONSTRAINT ck_fulfillment_saga_state CHECK (state IN
        ('RUNNING', 'RETRY_WAITING', 'CONFIG_REQUIRED', 'COMPLETED', 'ESCALATED')),
    CONSTRAINT ck_fulfillment_saga_outcome CHECK (outcome IS NULL OR outcome IN
        ('WORK_ACCEPTED', 'CANCEL_REQUESTED', 'CLOSED_BY_CANCEL')),
    CONSTRAINT ck_fulfillment_saga_version CHECK (work_version >= 1)
);
CREATE INDEX ix_fulfillment_saga_open ON fulfillment_saga (state, updated_at)
    WHERE state IN ('RUNNING', 'RETRY_WAITING', 'CONFIG_REQUIRED');

-- 작업 버전별 ConfirmOrder 시도. 결정은 mfc-fulfillment 응답 사본(정본은 fulfillment work_order_version).
CREATE TABLE fulfillment_attempt (
    saga_id       VARCHAR(64)  NOT NULL,
    work_version  INT          NOT NULL,
    request_id    VARCHAR(128) NOT NULL,            -- 하위 명령 멱등 키 sagaId:REQUEST_FULFILLMENT:v<n>
    decision      VARCHAR(8),                       -- ACCEPTED / REJECTED (응답 전·불명 null)
    reject_reason VARCHAR(32),
    requested_at  TIMESTAMPTZ  NOT NULL,
    decided_at    TIMESTAMPTZ,
    CONSTRAINT pk_fulfillment_attempt PRIMARY KEY (saga_id, work_version),
    CONSTRAINT fk_fulfillment_attempt_saga FOREIGN KEY (saga_id) REFERENCES fulfillment_saga (saga_id),
    CONSTRAINT ck_fulfillment_attempt_decision CHECK (decision IS NULL OR decision IN ('ACCEPTED', 'REJECTED'))
);
