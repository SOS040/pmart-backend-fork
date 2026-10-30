-- 권역·담당 MFC 매핑 버전과 주소 판정 — service-map.md delivery-serviceability, BackOffice BO-07.
-- 매핑 버전은 SERVICEABILITY 승인(ApprovalDecided)으로만 발효한다. 발효 버전은 하나. 기존 주문은 주문 당시 버전을 유지한다(여기서 바꾸지 않음).
-- 적용된 파일은 수정하지 않는다.

CREATE TABLE mapping_version (
    version             integer      NOT NULL,
    state               varchar(16)  NOT NULL,          -- DRAFT / APPROVED / ACTIVE / SUPERSEDED / REJECTED
    draft_hash          char(64)     NOT NULL,          -- 승인 요청에 잠근 내용 해시(승인 범위 대조)
    approval_id         varchar(64),
    approved_at         timestamptz,
    effective_from      timestamptz,
    superseded_at       timestamptz,
    change_summary      varchar(500),
    test_addresses      jsonb        NOT NULL,
    test_verified       integer      NOT NULL,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_mapping_version PRIMARY KEY (version),
    CONSTRAINT ck_mapping_version_state CHECK (state IN ('DRAFT', 'APPROVED', 'ACTIVE', 'SUPERSEDED', 'REJECTED'))
);
CREATE UNIQUE INDEX ux_mapping_version_active ON mapping_version ((1)) WHERE state = 'ACTIVE';
CREATE SEQUENCE mapping_version_seq START WITH 1;

CREATE TABLE mapping_zone (
    version             integer      NOT NULL,
    zone_id             varchar(40)  NOT NULL,
    mfc_id              varchar(40)  NOT NULL,
    name                varchar(100),
    priority            integer,
    geometry            jsonb        NOT NULL,          -- GeoJSON Polygon/MultiPolygon [경도, 위도]
    CONSTRAINT pk_mapping_zone PRIMARY KEY (version, zone_id),
    CONSTRAINT fk_mapping_zone_version FOREIGN KEY (version) REFERENCES mapping_version (version)
);

-- 판정 결과(checkId 로 재조회). 주소 원문은 남기지 않고 판정에 쓴 좌표·결과만
CREATE TABLE serviceability_check (
    check_id            varchar(40)  NOT NULL,
    result              varchar(16)  NOT NULL,
    mfc_id              varchar(40),
    zone_id             varchar(40),
    mapping_version     integer,
    overlapping         jsonb        NOT NULL,
    missing_fields      jsonb        NOT NULL,
    lat                 double precision,
    lng                 double precision,
    purpose             varchar(16),
    evaluated_at        timestamptz  NOT NULL,
    CONSTRAINT pk_serviceability_check PRIMARY KEY (check_id)
);

-- 같은 승인의 재적용 방지
CREATE TABLE approval_application (
    approval_id         varchar(64)  NOT NULL,
    target_ref          varchar(128) NOT NULL,
    result              varchar(32)  NOT NULL,
    applied_at          timestamptz  NOT NULL,
    CONSTRAINT pk_approval_application PRIMARY KEY (approval_id)
);
