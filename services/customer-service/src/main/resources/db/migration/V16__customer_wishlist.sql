-- 찜(2026-11-20 ADR-0081 — 사용자 "찜 기능 넣고 싶어"): 회원만, 상품 단위. 표시 값(상품명·가격·사진·판매 상태)은 저장하지 않는다 — 조회 때 product-content.
-- 최대 수는 정책값(pmart.customer.wishlist.max-items). 탈퇴 완료 때 지운다(ADR-0077).
CREATE TABLE customer_wishlist_item (
    account_id   varchar(64)  NOT NULL,
    product_id   varchar(64)  NOT NULL,
    added_at     timestamptz  NOT NULL,
    CONSTRAINT pk_customer_wishlist_item PRIMARY KEY (account_id, product_id)
);
CREATE INDEX ix_customer_wishlist_recent ON customer_wishlist_item (account_id, added_at DESC);
