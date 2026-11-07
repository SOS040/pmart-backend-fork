-- 운영 제한(BO-08, 2026-11-08). INTAKE_STOP = 신규 여력 확보(Hold) 거절, CAPACITY_REDUCE = 창 수용 단위 축소(기존 약속 유지).
-- 범위 = 슬롯 창이 [effective_from, effective_to) 와 겹치는 것(effective_to 없음 = 끝 없음). 시간이 지나도 자동 해제하지 않는다(ACTIVE 는 해제 요청으로만 LIFTED).
CREATE TABLE operation_restriction (
    restriction_id          varchar(64)  NOT NULL,
    mfc_id                  varchar(32)  NOT NULL,
    restriction_type        varchar(20)  NOT NULL,
    state                   varchar(10)  NOT NULL,
    approval_ref            varchar(128) NOT NULL,
    lift_approval_ref       varchar(128),
    reason                  varchar(500),
    effective_from          timestamptz  NOT NULL,
    effective_to            timestamptz,
    reduced_capacity_units  integer,
    affected_order_ids      jsonb        NOT NULL DEFAULT '[]'::jsonb,
    applied_by              varchar(64)  NOT NULL,
    lifted_by               varchar(64),
    version                 integer      NOT NULL DEFAULT 1,
    created_at              timestamptz  NOT NULL,
    lifted_at               timestamptz,
    CONSTRAINT pk_operation_restriction PRIMARY KEY (restriction_id),
    CONSTRAINT ck_operation_restriction_type CHECK (restriction_type IN ('INTAKE_STOP', 'CAPACITY_REDUCE')),
    CONSTRAINT ck_operation_restriction_state CHECK (state IN ('ACTIVE', 'LIFTED')),
    CONSTRAINT ck_operation_restriction_units CHECK (
        (restriction_type = 'CAPACITY_REDUCE' AND reduced_capacity_units IS NOT NULL AND reduced_capacity_units >= 0)
        OR (restriction_type = 'INTAKE_STOP' AND reduced_capacity_units IS NULL)),
    CONSTRAINT ck_operation_restriction_period CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_operation_restriction_active ON operation_restriction (mfc_id) WHERE state = 'ACTIVE';
