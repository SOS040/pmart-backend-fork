-- 회수품 반입(IV-10, 2026-11-08 사용자: "환불 격리로 뒀다가 품질 검수, 적격이면 재배치"). 반입 = inventory 수령(격리) 한 번, 검수 RESALE 분은 적치 작업.
CREATE TABLE return_receipt (
    return_receipt_id  varchar(48)  NOT NULL,
    mfc_id             varchar(32)  NOT NULL,
    claim_id           varchar(64)  NOT NULL,
    return_pickup_id   varchar(64)  NOT NULL,
    order_id           varchar(64),
    to_location        varchar(32)  NOT NULL,
    lines              jsonb        NOT NULL,
    state              varchar(16)  NOT NULL,
    commit_id          varchar(48),
    reject_reason      varchar(500),
    inspection_state   varchar(12)  NOT NULL DEFAULT 'PENDING',
    received_by        varchar(64)  NOT NULL,
    received_at        timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT pk_return_receipt PRIMARY KEY (return_receipt_id),
    CONSTRAINT ck_return_receipt_state CHECK (state IN ('SUBMITTED', 'APPLIED', 'REJECTED', 'UNKNOWN_RESULT')),
    CONSTRAINT ck_return_receipt_inspection CHECK (inspection_state IN ('PENDING', 'INSPECTED'))
);
CREATE INDEX ix_return_receipt_claim ON return_receipt (claim_id);
