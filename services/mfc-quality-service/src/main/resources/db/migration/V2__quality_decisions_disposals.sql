-- 품질 판정·폐기 승인·폐기 실행(2026-11-08, IV-09·IV-10). 수량 정본은 mfc-inventory — 여기는 판단·승인 기록과 재고 반영 결과(commitId)만.

-- 격리(QUARANTINE)·해제(RELEASE) 판정. 해제는 요청(REQUESTED) → 다른 운영자 승인 → 재고 반영.
-- state: REQUESTED(해제 승인 대기) / SUBMITTED(재고 요청 중) / APPLIED / UNKNOWN_RESULT(같은 키로 다시) / REJECTED(재고 거절) / DECLINED(해제 반려)
CREATE TABLE quality_decision (
    decision_id         varchar(64)  NOT NULL,
    mfc_id              varchar(40)  NOT NULL,
    decision_type       varchar(16)  NOT NULL,
    state               varchar(16)  NOT NULL,
    reason              varchar(32)  NOT NULL,
    decided_by          varchar(64)  NOT NULL,
    approver_id         varchar(64),
    incident_id         varchar(64),
    note                varchar(500),
    decline_note        varchar(500),
    evidence_refs       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    lines               jsonb        NOT NULL,   -- [{sku, lotId, locationCode, qty, workState}]
    inventory_commit_id varchar(64),
    applied_qty         integer      NOT NULL DEFAULT 0,
    reject_reason       varchar(300),
    version             integer      NOT NULL DEFAULT 1,
    created_at          timestamptz  NOT NULL,
    approved_at         timestamptz,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_quality_decision PRIMARY KEY (decision_id),
    CONSTRAINT ck_quality_decision_type CHECK (decision_type IN ('QUARANTINE', 'RELEASE')),
    CONSTRAINT ck_quality_decision_state CHECK (state IN ('REQUESTED', 'SUBMITTED', 'APPLIED', 'UNKNOWN_RESULT', 'REJECTED', 'DECLINED'))
);
CREATE INDEX ix_quality_decision_open ON quality_decision (updated_at) WHERE state IN ('SUBMITTED', 'UNKNOWN_RESULT');

-- 폐기 승인. lines = [{sku, lotId, locationCode, requestedQty, approvedQty, executedQty}]
CREATE TABLE disposal_approval (
    disposal_approval_id varchar(64)  NOT NULL,
    mfc_id               varchar(40)  NOT NULL,
    state                varchar(24)  NOT NULL,
    requester_id         varchar(64)  NOT NULL,
    approver_id          varchar(64),
    approval_ref         varchar(128),
    reason               varchar(500),
    source_ref           varchar(128),
    lines                jsonb        NOT NULL,
    version              integer      NOT NULL DEFAULT 1,
    requested_at         timestamptz  NOT NULL,
    decided_at           timestamptz,
    updated_at           timestamptz  NOT NULL,
    CONSTRAINT pk_disposal_approval PRIMARY KEY (disposal_approval_id),
    CONSTRAINT ck_disposal_approval_state CHECK (state IN ('REQUESTED', 'APPROVED', 'REJECTED', 'PARTIALLY_EXECUTED', 'COMPLETED'))
);

-- 폐기 실행(재고 DISPOSE 한 번). 진행 중(SUBMITTED·UNKNOWN_RESULT)은 승인 잔량을 점유한다.
CREATE TABLE disposal_execution (
    execution_id         varchar(64)  NOT NULL,
    disposal_approval_id varchar(64)  NOT NULL,
    request_key          varchar(128) NOT NULL,
    state                varchar(16)  NOT NULL,
    executed_by          varchar(64)  NOT NULL,
    lines                jsonb        NOT NULL,   -- [{sku, lotId, locationCode, qty, workState}]
    qty                  integer      NOT NULL,
    note                 varchar(500),
    inventory_commit_id  varchar(64),
    reject_reason        varchar(300),
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    CONSTRAINT pk_disposal_execution PRIMARY KEY (execution_id),
    CONSTRAINT ux_disposal_execution_key UNIQUE (disposal_approval_id, request_key),
    CONSTRAINT fk_disposal_execution_approval FOREIGN KEY (disposal_approval_id) REFERENCES disposal_approval (disposal_approval_id),
    CONSTRAINT ck_disposal_execution_state CHECK (state IN ('SUBMITTED', 'APPLIED', 'UNKNOWN_RESULT', 'REJECTED'))
);
CREATE INDEX ix_disposal_execution_open ON disposal_execution (updated_at) WHERE state IN ('SUBMITTED', 'UNKNOWN_RESULT');
