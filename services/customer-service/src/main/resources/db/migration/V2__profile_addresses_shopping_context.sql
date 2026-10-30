-- 고객 프로필·주소록·현재 쇼핑 주소·비회원 배송지 (2026-10-30, 고객앱 우선 범위 ①).
-- 권역 판정 결과는 delivery-serviceability 의 사본(svc_*)이다. 저장 성공과 배송 가능은 다르다(Commerce 5.1).

CREATE TABLE customer_profile (
    account_id          varchar(64)  NOT NULL,
    status              varchar(24)  NOT NULL,          -- ACTIVE / WITHDRAW_PENDING / WITHDRAW_COMPLETED
    social_provider     varchar(16),                    -- CustomerAuthenticated.provider (먼저 /me 가 오면 비어 있다)
    nickname            varchar(50),
    joined_at           timestamptz  NOT NULL,
    last_authenticated_at timestamptz,
    version             integer      NOT NULL DEFAULT 0,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_customer_profile PRIMARY KEY (account_id)
);

-- 회원 주소록과 비회원 배송지는 같은 모양. owner_type 으로 구분(ACCOUNT=accountId, GUEST=기기 ID).
CREATE TABLE customer_address (
    address_id          varchar(64)  NOT NULL,
    owner_type          varchar(8)   NOT NULL,          -- ACCOUNT / GUEST
    owner_id            varchar(128) NOT NULL,
    label               varchar(20),
    road_address        varchar(200) NOT NULL,
    detail_address      varchar(200) NOT NULL,
    postal_code         varchar(10),
    receiver_name       varchar(50)  NOT NULL,
    receiver_phone      varchar(20)  NOT NULL,
    entrance_method     varchar(16),
    entrance_note       varchar(100),                   -- 출입 비밀번호일 수 있다. 로그 금지
    lat                 double precision,
    lng                 double precision,
    svc_state           varchar(24)  NOT NULL,          -- SERVICEABLE / OUT_OF_SERVICE_AREA / UNKNOWN
    svc_result          varchar(24),                    -- 판정 원결과(OVERLAP, NEEDS_INPUT 등)
    svc_mfc_id          varchar(64),
    svc_zone_id         varchar(64),
    svc_mapping_version integer,
    svc_check_id        varchar(64),
    svc_as_of           timestamptz,
    version             integer      NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_customer_address PRIMARY KEY (address_id)
);
CREATE INDEX ix_customer_address_owner ON customer_address (owner_type, owner_id, created_at);

CREATE TABLE shopping_context (
    account_id          varchar(64)  NOT NULL,
    shopping_address_id varchar(64),
    mfc_id              varchar(64),
    zone_id             varchar(64),
    serviceable         boolean      NOT NULL DEFAULT false,
    context_version     integer      NOT NULL DEFAULT 0,
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_shopping_context PRIMARY KEY (account_id)
);
