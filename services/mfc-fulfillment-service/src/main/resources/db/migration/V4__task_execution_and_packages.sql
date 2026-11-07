-- 슬라이스 6b(2026-11-07): 점원 단말 작업 시작·피킹 실적·포장 밀봉(WK-03·WK-05·WK-07, IV-07).

-- 준비 시작(D6-1 = 그 주문의 첫 PICK 시작). PreparationStarted 는 한 번만.
ALTER TABLE work_order ADD COLUMN preparation_started_at timestamptz;

-- 담당(mfc-capacity AssignmentPublished 투영)과 활성 시작 점유
ALTER TABLE task
    ADD COLUMN worker_id          varchar(64),
    ADD COLUMN assignment_id      varchar(48),
    ADD COLUMN assignment_version integer      NOT NULL DEFAULT 0,
    ADD COLUMN start_request_id   varchar(128),
    ADD COLUMN started_by         varchar(64),
    ADD COLUMN started_at         timestamptz,
    ADD COLUMN device_id          varchar(64),
    ADD COLUMN block_reason       varchar(300);

-- 시작 판정(STARTED / REJECTED) — 같은 Idempotency-Key 는 기록된 판정
CREATE TABLE task_start (
    task_id            varchar(96)  NOT NULL,
    request_id         varchar(128) NOT NULL,
    outcome            varchar(8)   NOT NULL,
    reject_code        varchar(32),
    worker_id          varchar(64)  NOT NULL,
    assignment_version integer      NOT NULL,
    task_version       integer      NOT NULL,
    decided_at         timestamptz  NOT NULL,
    CONSTRAINT pk_task_start PRIMARY KEY (task_id, request_id),
    CONSTRAINT fk_task_start_task FOREIGN KEY (task_id) REFERENCES task (task_id),
    CONSTRAINT ck_task_start_outcome CHECK (outcome IN ('STARTED', 'REJECTED'))
);

-- 피킹 실적. RECORDED(접수) → APPLIED / REJECTED, 재고 응답 불명은 UNKNOWN_RESULT(같은 키로 다시 → 같은 실행 ID)
CREATE TABLE task_execution (
    execution_id        varchar(64)  NOT NULL,
    task_id             varchar(96)  NOT NULL,
    request_id          varchar(128) NOT NULL,
    worker_id           varchar(64)  NOT NULL,
    execution_type      varchar(8)   NOT NULL,
    from_location       varchar(64),
    to_location         varchar(64),
    lines               jsonb        NOT NULL,   -- [{orderLineId, sku, lotId, pickedQty, shortage{qty, reason, note}}]
    state               varchar(16)  NOT NULL,
    inventory_commit_id varchar(64),
    applied_qty         integer      NOT NULL DEFAULT 0,
    reject_reason       varchar(300),
    recorded_at         timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_task_execution PRIMARY KEY (execution_id),
    CONSTRAINT ux_task_execution_request UNIQUE (task_id, request_id),
    CONSTRAINT fk_task_execution_task FOREIGN KEY (task_id) REFERENCES task (task_id),
    CONSTRAINT ck_task_execution_state CHECK (state IN ('RECORDED', 'APPLIED', 'REJECTED', 'UNKNOWN_RESULT'))
);

-- 포장. OPEN(접수, 재고 PACK 반영 전) → SEALED / VOIDED(재고 거절). 같은 Idempotency-Key 는 같은 포장
CREATE TABLE package (
    package_id          varchar(64)  NOT NULL,
    shipment_id         varchar(80)  NOT NULL,
    request_id          varchar(128) NOT NULL,
    package_version     integer      NOT NULL DEFAULT 1,
    state               varchar(16)  NOT NULL,
    qr_code             varchar(200),
    contents            jsonb        NOT NULL,   -- [{orderLineId, sku, lotId, qty}]
    bag_count           integer      NOT NULL,
    contains_alcohol    boolean      NOT NULL DEFAULT false,
    temperature_zone    varchar(10),
    weight_g            integer,
    volume_cm3          integer,
    packed_by           varchar(64)  NOT NULL,
    packing_location    varchar(64)  NOT NULL,
    inventory_commit_id varchar(64),
    reject_reason       varchar(300),
    version             integer      NOT NULL DEFAULT 1,
    created_at          timestamptz  NOT NULL,
    sealed_at           timestamptz,
    CONSTRAINT pk_package PRIMARY KEY (package_id),
    CONSTRAINT ux_package_request UNIQUE (shipment_id, request_id),
    CONSTRAINT fk_package_shipment FOREIGN KEY (shipment_id) REFERENCES shipment (shipment_id),
    CONSTRAINT ck_package_state CHECK (state IN ('OPEN', 'SEALED', 'HANDED_OVER', 'INVALIDATED', 'VOIDED'))
);
CREATE INDEX ix_package_shipment ON package (shipment_id);

ALTER TABLE shipment ADD COLUMN ready_at timestamptz;
