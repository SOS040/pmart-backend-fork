-- 주문 당시 배송지·수령 연락처 사본(BR-37, OMS 153행 "주소·연락처 … 주문 당시 값으로 보존", Commerce 3351행 "소급 변경하지 않는다").
-- 초안을 만들 때 customer-service 배송지를 복사해 초안 스냅샷(JSON)에 고정하고, 주문 확정 트랜잭션에서 이 표로 옮긴다.
-- 주문 원장(customer_order)과 나눠 둔다: 개인정보라 보존·가림·삭제 정책을 따로 적용할 수 있게. 이벤트·로그에 넣지 않는다.
-- 2026-11-02 이전 주문은 행이 없다(사본 없음 → 응답 null).
CREATE TABLE customer_order_shipping (
    order_id         varchar(64)      NOT NULL,
    road_address     varchar(200)     NOT NULL,
    detail_address   varchar(200)     NOT NULL,
    postal_code      varchar(10),
    receiver_name    varchar(50)      NOT NULL,
    receiver_phone   varchar(20)      NOT NULL,
    entrance_method  varchar(16),
    entrance_note    varchar(100),                  -- 출입 비밀번호일 수 있다. 로그 금지
    lat              double precision,
    lng              double precision,
    captured_at      timestamptz      NOT NULL,     -- 사본을 뜬 시각(초안 생성)
    CONSTRAINT pk_customer_order_shipping PRIMARY KEY (order_id),
    CONSTRAINT fk_customer_order_shipping_order FOREIGN KEY (order_id) REFERENCES customer_order (order_id)
);
