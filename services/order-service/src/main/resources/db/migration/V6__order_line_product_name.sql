-- 주문행의 고객용 상품명·옵션 라벨 사본(BR-35). 초안을 만들 때 product-content 주문 스냅샷에서 복사해 고정한 값을 주문 확정 때 옮긴다.
-- 이후 상품명이 바뀌어도 과거 주문은 그대로(Commerce "주문 당시 고정 사본"). 조회에 실패했던 행·2026-11-02 이전 주문은 NULL.
ALTER TABLE customer_order_line
    ADD COLUMN product_name varchar(200),
    ADD COLUMN option_label varchar(100);
