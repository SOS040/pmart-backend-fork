-- 주문 기본 설정(2026-11-18, 계약 0.4.0 — 고객 앱 "다음에도 이 품절 처리 적용", 사용자 "A해줘").
-- 앱이 주문서 기본값으로만 쓴다. order-service 는 읽지 않는다(주문마다 초안에 보낸 값이 정본). 개인정보 아님.
-- 행이 없으면 저장한 적 없음(null). 프로필 버전(customer.version)과 따로 센다 — 설정을 바꿔도 프로필 버전은 그대로.
CREATE TABLE customer_order_preference (
    account_id               varchar(64)  NOT NULL,
    out_of_stock_preference  varchar(20)  NOT NULL,   -- CANCEL_OOS_ONLY / CANCEL_ALL (order-service 와 같은 값)
    version                  integer      NOT NULL,   -- 1부터, 바뀔 때마다 +1
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT pk_customer_order_preference PRIMARY KEY (account_id),
    CONSTRAINT ck_customer_order_preference_oos CHECK (out_of_stock_preference IN ('CANCEL_OOS_ONLY', 'CANCEL_ALL'))
);
