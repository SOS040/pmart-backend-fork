-- 주문 초안(2026-10-31, 고객앱 우선 범위 ⑤). 금액·품목·배송 조건을 초안 시점에 고정한다(가격 동결 10분).
-- 결제 진입(checkouts)·자원 확보·주문 확정은 이후 단계(자원 확보 사가).
CREATE TABLE order_draft (
    draft_id           varchar(64)  NOT NULL,
    account_id         varchar(64)  NOT NULL,
    purchase_intent_id varchar(128) NOT NULL,
    status             varchar(20)  NOT NULL,          -- DRAFT (만료는 조회 때 quote_expires_at 로 판정)
    mfc_id             varchar(40)  NOT NULL,
    snapshot           jsonb        NOT NULL,          -- 품목·배송·견적·차단 사유
    version            integer      NOT NULL DEFAULT 1,
    created_at         timestamptz  NOT NULL,
    quote_expires_at   timestamptz  NOT NULL,
    CONSTRAINT pk_order_draft PRIMARY KEY (draft_id)
);
CREATE INDEX ix_order_draft_intent ON order_draft (account_id, purchase_intent_id, created_at DESC);
