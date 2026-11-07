-- 라이더 인계(슬라이스 6c, 2026-11-08, ADR-0047). 인계 계약: docs/business/flows/package-handover-contract.md.

-- 배송 배정 투영(delivery-dispatch 사건: DeliveryCreated·DeliveryAssigned·DeliveryAssignmentChanged·DeliveryCancelledBeforePickup).
-- 사건 순서가 섞여도 되게 배송 ID 로 한 행(출고는 DeliveryCreated 가 채운다). 버전은 더 새 것만.
CREATE TABLE delivery_assignment (
    delivery_id        varchar(64)  NOT NULL,
    shipment_id        varchar(80),
    order_id           varchar(64),
    rider_id           varchar(64),
    assignment_version integer      NOT NULL DEFAULT 0,
    state              varchar(16)  NOT NULL,   -- CREATED / ASSIGNED / UNASSIGNED / CANCELLED
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT pk_delivery_assignment PRIMARY KEY (delivery_id),
    CONSTRAINT ck_delivery_assignment_state CHECK (state IN ('CREATED', 'ASSIGNED', 'UNASSIGNED', 'CANCELLED'))
);
CREATE INDEX ix_delivery_assignment_shipment ON delivery_assignment (shipment_id);

-- 포장의 인계 허가(fence). 만료는 저장 상태가 아니라 시각으로 판단(확정 요청 전일 때만 끝난 것으로 봄).
ALTER TABLE package
    ADD COLUMN fence_state               varchar(24)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN handover_authorization_id varchar(64),
    ADD COLUMN fence_version             integer      NOT NULL DEFAULT 0,
    ADD COLUMN fence_rider_id            varchar(64),
    ADD COLUMN fence_delivery_id         varchar(64),
    ADD COLUMN fence_expires_at          timestamptz,
    ADD COLUMN fence_authorized_at       timestamptz,
    ADD COLUMN handed_over_at            timestamptz,
    ADD CONSTRAINT ck_package_fence CHECK (fence_state IN ('NONE', 'HANDOVER_IN_PROGRESS', 'COMMITTED', 'EXPIRED'));

-- 인계 사실(HOV). 포장당 하나. REQUESTED(재고 요청 전·중) → APPLIED / UNKNOWN_RESULT(같은 ID 로 재시도) / REJECTED(대사 필요)
CREATE TABLE package_handover (
    handover_id               varchar(64)  NOT NULL,
    package_id                varchar(64)  NOT NULL,
    shipment_id               varchar(80)  NOT NULL,
    handover_authorization_id varchar(64)  NOT NULL,
    fence_version             integer      NOT NULL,
    rider_id                  varchar(64)  NOT NULL,
    delivery_id               varchar(64)  NOT NULL,
    state                     varchar(16)  NOT NULL,
    inventory_commit_id       varchar(64),
    reject_reason             varchar(300),
    requested_at              timestamptz  NOT NULL,
    updated_at                timestamptz  NOT NULL,
    handed_over_at            timestamptz,
    CONSTRAINT pk_package_handover PRIMARY KEY (handover_id),
    CONSTRAINT ux_package_handover_package UNIQUE (package_id),
    CONSTRAINT fk_package_handover_package FOREIGN KEY (package_id) REFERENCES package (package_id),
    CONSTRAINT ck_package_handover_state CHECK (state IN ('REQUESTED', 'APPLIED', 'UNKNOWN_RESULT', 'REJECTED'))
);
CREATE INDEX ix_package_handover_open ON package_handover (updated_at) WHERE state IN ('REQUESTED', 'UNKNOWN_RESULT');
