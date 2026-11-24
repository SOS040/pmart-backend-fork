-- 클레임 품목의 상품 사본(2026-11-24 BR-92 — 고객 앱 요청 "취소·반품 내역에 상품이 SKU 로 보여").
-- 접수 때 order 내부 원장의 주문 줄 값: 상품명·옵션 라벨은 주문 당시 사본, 사진은 접수 때의 대표 사진. 이름 사본이 없는 옛 주문·이 판 전 행은 NULL.
ALTER TABLE claim_line ADD COLUMN product_name text;
ALTER TABLE claim_line ADD COLUMN option_label text;
ALTER TABLE claim_line ADD COLUMN image_url text;
