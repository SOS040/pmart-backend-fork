-- 반영 회신(ApplicationReceipt, ADR-0039): 정본 서비스가 ApplicationReceiptRecorded(platform.approval-receipt.v1)로 알린
-- 반영 결과를 수신자·순번별로 남긴다. 승인의 실행 축(execution_state)은 마지막 회신의 결과로 바뀐다. 적용된 파일은 수정하지 않는다.

CREATE TABLE application_receipt (
    receipt_id          varchar(40)  NOT NULL,
    approval_id         varchar(40)  NOT NULL,
    receiver_service    varchar(64)  NOT NULL,
    receipt_seq         integer      NOT NULL,
    result              varchar(24)  NOT NULL,          -- ACCEPTED / APPLIED / PARTIAL / REJECTED / UNKNOWN_RESULT
    domain_ref          varchar(128),
    reason              varchar(500),
    lines               jsonb        NOT NULL,          -- 행별 결과(이벤트 lines 사본, 없으면 [])
    occurred_at         timestamptz  NOT NULL,          -- 정본이 반영한 시각(이벤트 occurredAt)
    received_at         timestamptz  NOT NULL,
    event_id            varchar(64)  NOT NULL,
    CONSTRAINT pk_application_receipt PRIMARY KEY (receipt_id),
    CONSTRAINT fk_receipt_approval FOREIGN KEY (approval_id) REFERENCES approval (approval_id),
    -- 같은 수신자·순번 재전송은 한 번만(멱등)
    CONSTRAINT ux_receipt_receiver_seq UNIQUE (approval_id, receiver_service, receipt_seq)
);
CREATE INDEX ix_receipt_approval ON application_receipt (approval_id, received_at);
