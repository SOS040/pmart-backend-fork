-- 센터 기준정보·영업 시간표(2026-10-31, 고객앱 우선 범위 ⑤). 변경은 approval-service CENTER_MASTER 승인(BO-06)으로만.

CREATE TABLE center (
    center_id         varchar(40)  NOT NULL,
    version           integer      NOT NULL,
    type              varchar(8)   NOT NULL,          -- CDC / MFC
    name              varchar(100) NOT NULL,
    state             varchar(20)  NOT NULL,          -- PLANNED / ACTIVE / TEMPORARILY_CLOSED / CLOSING / CLOSED
    address           varchar(300),
    lat               double precision,
    lng               double precision,
    temperature_zones jsonb        NOT NULL DEFAULT '[]',
    profile           jsonb        NOT NULL DEFAULT '{}',  -- 계약 Center 의 나머지(진입 위치·층수·반입 조건 등)
    approval_ref      varchar(64)  NOT NULL,
    approved_by       varchar(64),
    effective_from    timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT pk_center PRIMARY KEY (center_id)
);

-- 다섯 시간표(CUSTOMER_ORDER·STAFF·VEHICLE_ACCESS·UNLOADING·PUTAWAY) 중 승인된 것. 없으면 그 종류는 CONFIG_REQUIRED.
CREATE TABLE center_timetable (
    center_id     varchar(40)  NOT NULL,
    kind          varchar(20)  NOT NULL,
    version       integer      NOT NULL,
    weekly        jsonb        NOT NULL,          -- {"MON":[["08:00","24:00"]], ...}
    holidays      jsonb        NOT NULL DEFAULT '[]',
    restrictions  jsonb        NOT NULL DEFAULT '[]',  -- [{"date","start","end","reason"}]
    approval_ref  varchar(64)  NOT NULL,
    approved_by   varchar(64),
    effective_from timestamptz NOT NULL,
    CONSTRAINT pk_center_timetable PRIMARY KEY (center_id, kind)
);

CREATE TABLE approval_application (
    approval_id varchar(64)  NOT NULL,
    result      varchar(200) NOT NULL,
    applied_at  timestamptz  NOT NULL,
    CONSTRAINT pk_center_approval_application PRIMARY KEY (approval_id)
);
