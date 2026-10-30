-- 전사 판매 상태 변경·SKU 발급 확인 명령 — product-service.yaml commands, BO-03 4).
-- 판매중지(SALE_STOPPED)는 PRODUCT 승인이 필요하고, 일시품절·판매 재개는 MD 가 바로 바꾼다(사용자 결정 2026-10-30).
-- 적용된 파일은 수정하지 않는다.

-- 판매 상태 변경 승인(ApprovalDecided PRODUCT, targetRef SALE-STATE:{productId}) 수신 기록. 명령이 쓰면 consumed.
CREATE TABLE sale_state_grant (
    approval_id         varchar(40)  NOT NULL,
    product_id          varchar(40)  NOT NULL,
    sale_state          varchar(24)  NOT NULL,
    decision            varchar(24)  NOT NULL,          -- APPROVED / REJECTED
    consumed_request_id varchar(128),
    received_at         timestamptz  NOT NULL,
    CONSTRAINT pk_sale_state_grant PRIMARY KEY (approval_id)
);

-- 판매 상태 변경 명령 결과(requestId 로 재조회)
CREATE TABLE sale_state_change (
    product_id          varchar(40)  NOT NULL,
    request_id          varchar(128) NOT NULL,
    from_state          varchar(24)  NOT NULL,
    to_state            varchar(24)  NOT NULL,
    reason              varchar(200) NOT NULL,
    approval_id         varchar(40),
    actor               varchar(128) NOT NULL,
    response            jsonb        NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_sale_state_change PRIMARY KEY (product_id, request_id)
);

-- SKU 발급 확인 명령(requestId → 승인으로 발급된 SKU)
CREATE TABLE sku_issuance_request (
    request_id          varchar(128) NOT NULL,
    source_system       varchar(64)  NOT NULL,
    approval_id         varchar(40)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    product_id          varchar(40)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_sku_issuance_request PRIMARY KEY (request_id)
);
