-- 회수품 검수(IV-10, 2026-11-08 사용자: "환불 격리로 뒀다가 품질 검수, 적격이면 재배치").
-- 반입 사본: mfc-inbound ReturnItemReceived(줄별 inventory lot·반품 위치·반입자) — 검수는 이 사본을 기준으로.
CREATE TABLE return_receipt_projection (
    return_receipt_id  varchar(48)  NOT NULL,
    mfc_id             varchar(32)  NOT NULL,
    claim_id           varchar(64)  NOT NULL,
    lines              jsonb        NOT NULL,
    received_by        varchar(64),
    received_at        timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL,
    CONSTRAINT pk_return_receipt_projection PRIMARY KEY (return_receipt_id)
);

CREATE TABLE return_inspection (
    return_inspection_id  varchar(48)  NOT NULL,
    mfc_id                varchar(32)  NOT NULL,
    claim_id              varchar(64)  NOT NULL,
    return_receipt_id     varchar(48)  NOT NULL,
    inspected_by          varchar(64)  NOT NULL,
    lines                 jsonb        NOT NULL,
    resale_qty            integer      NOT NULL,
    dispose_qty           integer      NOT NULL,
    apply_status          varchar(16)  NOT NULL,
    inventory_commit_id   varchar(48),
    disposal_approval_id  varchar(48),
    reject_reason         varchar(500),
    version               integer      NOT NULL,
    inspected_at          timestamptz  NOT NULL,
    CONSTRAINT pk_return_inspection PRIMARY KEY (return_inspection_id),
    CONSTRAINT uq_return_inspection_receipt UNIQUE (return_receipt_id),
    CONSTRAINT ck_return_inspection_status CHECK (apply_status IN ('SUBMITTED', 'APPLIED', 'REJECTED', 'UNKNOWN_RESULT'))
);
