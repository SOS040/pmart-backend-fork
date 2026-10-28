-- approval-service 최소 범위 (ADR-0014, 구현 계획 슬라이스 1): 변경 요청 → 제출(승인 단위 확정) → 단일 승인자 결정 → ApprovalDecided.
-- 다단 결재·부분 승인·증빙 요청·대행·일괄 변경·반영 회신(Receipt)은 이후 단계. 적용된 파일은 수정하지 않는다.

CREATE TABLE change_request (
    change_request_id   varchar(40)  NOT NULL,
    version             integer      NOT NULL,
    type                varchar(32)  NOT NULL,
    target_service      varchar(64)  NOT NULL,
    target_ref          varchar(128) NOT NULL,
    request_state       varchar(24)  NOT NULL,          -- DRAFT / SUBMITTED / WITHDRAW_REQUESTED / WITHDRAWN
    requester_id        varchar(128) NOT NULL,
    approval_id         varchar(40),
    body                jsonb        NOT NULL,          -- 요청 본문(ChangeRequestBody) 스냅샷
    created_at          timestamptz  NOT NULL,
    submitted_at        timestamptz,
    CONSTRAINT pk_change_request PRIMARY KEY (change_request_id)
);

CREATE TABLE approval (
    approval_id         varchar(40)  NOT NULL,
    version             integer      NOT NULL,
    change_request_id   varchar(40)  NOT NULL,
    type                varchar(32)  NOT NULL,
    target_service      varchar(64)  NOT NULL,
    target_ref          varchar(128) NOT NULL,
    requester_id        varchar(128) NOT NULL,
    review_state        varchar(24)  NOT NULL,
    execution_state     varchar(24)  NOT NULL,
    delivery_state      varchar(24)  NOT NULL,
    unit                jsonb        NOT NULL,          -- 제출 시 확정한 승인 단위(본문 사본). 결정 후 바뀌지 않는다
    effective_from      timestamptz,
    expires_at          timestamptz,
    submitted_at        timestamptz  NOT NULL,
    CONSTRAINT pk_approval PRIMARY KEY (approval_id),
    CONSTRAINT fk_approval_change_request FOREIGN KEY (change_request_id) REFERENCES change_request (change_request_id)
);
-- 이중 발급 금지: 같은 종류·정본·원객체의 열린(심사 중) 승인은 하나
CREATE UNIQUE INDEX ux_approval_open_target ON approval (type, target_service, target_ref)
    WHERE review_state IN ('REVIEW_PENDING', 'EVIDENCE_REQUIRED');
CREATE INDEX ix_approval_inbox ON approval (review_state, submitted_at DESC, approval_id DESC);

CREATE TABLE approval_decision (
    decision_id         varchar(40)  NOT NULL,
    approval_id         varchar(40)  NOT NULL,
    decision            varchar(24)  NOT NULL,          -- APPROVE / REJECT (최소 범위)
    approver_id         varchar(128) NOT NULL,
    body                jsonb        NOT NULL,          -- 결정 요청(DecisionRequest) 스냅샷
    decided_at          timestamptz  NOT NULL,
    CONSTRAINT pk_approval_decision PRIMARY KEY (decision_id),
    CONSTRAINT fk_decision_approval FOREIGN KEY (approval_id) REFERENCES approval (approval_id)
);
CREATE INDEX ix_approval_decision_approval ON approval_decision (approval_id, decided_at);
