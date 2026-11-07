-- 순환 실사(IV-11, 2026-11-08). 실사 잠금 위치의 판매 단위(NORMAL·STORED) 장부를 기준 시각에 고정하고, 계수 → (차이면) 다른 작업자 재계수
-- → 감독자 승인 → 한 확정 단위(COUNT_ADJ)로 반영. 첫 계수 때 장부는 보이지 않는다.
-- book        = [{locationCode, sku, lotId, qty, heldQty}]   기준 시각 장부(조사 보류 포함)
-- first_count = [{locationCode, sku, lotId, countedQty}]     첫 계수
-- recount     = 같은 모양(다른 작업자)
CREATE TABLE count_session (
    session_id      varchar(64)  NOT NULL,
    mfc_id          varchar(32)  NOT NULL,
    state           varchar(20)  NOT NULL,
    location_codes  jsonb        NOT NULL,
    reason          varchar(200),
    created_by      varchar(64)  NOT NULL,
    first_counter   varchar(64),
    recounter       varchar(64),
    approver_id     varchar(64),
    approval_ref    varchar(128),
    decision_note   varchar(500),
    book            jsonb        NOT NULL,
    first_count     jsonb,
    recount         jsonb,
    commit_id       varchar(64),
    version         integer      NOT NULL DEFAULT 1,
    baseline_at     timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT pk_count_session PRIMARY KEY (session_id),
    CONSTRAINT ck_count_session_state CHECK (state IN ('COUNTING', 'RECOUNT_REQUIRED', 'PENDING_APPROVAL', 'MATCHED', 'ADJUSTED', 'REJECTED'))
);
CREATE INDEX ix_count_session_open ON count_session (mfc_id) WHERE state IN ('COUNTING', 'RECOUNT_REQUIRED', 'PENDING_APPROVAL');

ALTER TABLE stock_hold ADD COLUMN resolved_ref varchar(128);
