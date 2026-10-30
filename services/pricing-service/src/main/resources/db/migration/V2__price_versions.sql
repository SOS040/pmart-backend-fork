-- 기본 판매가격 버전·가격 승인 반영·견적 — service-map.md pricing-service, BackOffice BO-04, pricing-service.yaml.
-- 가격 버전은 PRICE 승인(ApprovalDecided) 결과로만 만든다. 적용된 파일은 수정하지 않는다.

-- ---------- product-service 사본 (SkuIssued·ProductVersionPublished 투영, 무효 SKU 검사용) ----------
CREATE TABLE sku_projection (
    sku                 varchar(10)  NOT NULL,          -- E-00000001 / B-00000001
    product_id          varchar(64),
    state               varchar(16)  NOT NULL,          -- ACTIVE / INACTIVE
    product_version     integer      NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_sku_projection PRIMARY KEY (sku),
    CONSTRAINT ck_sku_projection_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);

-- ---------- 가격 버전 (SKU별, 전사 동일·센터 예외 없음) ----------
-- 효력 시각은 버전 순서대로 늘어난다(예약 버전보다 이른 새 버전은 거절). 그래서 "시각 t 의 가격" = effective_from <= t 인 가장 큰 버전.
-- state 는 발효 사건(PriceVersionEffective) 발행 여부를 나타낸다. SCHEDULED → EFFECTIVE → SUPERSEDED.
CREATE TABLE price_version (
    sku                     varchar(10)  NOT NULL,
    version                 integer      NOT NULL,
    base_price              bigint       NOT NULL,      -- KRW 정수. 0원 허용 여부는 미정이라 받지 않는다(BO-04)
    previous_price          bigint,
    effective_from          timestamptz  NOT NULL,      -- 실제 효력 시각(요청 시각이 지났으면 반영 시각, 소급 없음)
    requested_effective_from timestamptz,               -- 승인에 적힌 효력 시각
    effective_to            timestamptz,
    state                   varchar(16)  NOT NULL,
    approval_id             varchar(64)  NOT NULL,
    change_request_id       varchar(64),
    approver_id             varchar(64),
    dossier_version         integer,
    created_at              timestamptz  NOT NULL,
    effective_published_at  timestamptz,
    CONSTRAINT pk_price_version PRIMARY KEY (sku, version),
    CONSTRAINT ck_price_version_state CHECK (state IN ('SCHEDULED', 'EFFECTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_price_version_price CHECK (base_price > 0)
);
CREATE INDEX ix_price_version_due ON price_version (effective_from) WHERE state = 'SCHEDULED';

-- ---------- 가격 승인 수신 기록 (승인 범위의 정본 사본, 같은 승인의 재적용 방지) ----------
CREATE TABLE price_approval (
    approval_id         varchar(64)  NOT NULL,
    change_request_id   varchar(64),
    decision            varchar(24)  NOT NULL,          -- APPROVED / REJECTED / PARTIALLY_APPROVED
    result              varchar(16)  NOT NULL,          -- APPLIED / PARTIAL / REJECTED
    received_at         timestamptz  NOT NULL,
    CONSTRAINT pk_price_approval PRIMARY KEY (approval_id)
);

-- 승인 행별 결과. 승인 범위 검증(/price-version-applications)은 이 표와 대조한다.
CREATE TABLE price_approval_line (
    approval_id              varchar(64)  NOT NULL,
    sku                      varchar(64)  NOT NULL,     -- 승인 행에 온 값 그대로(형식 오류 행도 남긴다)
    base_price               bigint,
    requested_effective_from timestamptz,
    state                    varchar(16)  NOT NULL,     -- APPLIED / REJECTED
    reason_code              varchar(48),
    price_version            integer,
    CONSTRAINT pk_price_approval_line PRIMARY KEY (approval_id, sku),
    CONSTRAINT ck_price_approval_line_state CHECK (state IN ('APPLIED', 'REJECTED'))
);

-- ---------- 가격 버전 반영 명령 (requestId → 승인) ----------
CREATE TABLE price_version_application (
    request_id          varchar(128) NOT NULL,
    source_system       varchar(64)  NOT NULL,
    approval_id         varchar(64)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_price_version_application PRIMARY KEY (request_id)
);

-- ---------- 견적 (order 초안이 quoteId·priceVersion 으로 스냅샷, 응답 유실 시 재조회) ----------
CREATE TABLE price_quote (
    quote_id            varchar(64)  NOT NULL,
    request_id          varchar(128) NOT NULL,
    body                jsonb        NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_price_quote PRIMARY KEY (quote_id)
);
