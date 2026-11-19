-- 주문 배송지 사본의 받는 분 이름을 선택으로(2026-11-19, 계약 0.14.0 — 클라이언트 BR-60 R4). 주소·프로필 모두 이름이 없으면(드묾) 비운다.
-- 연락처는 그대로 필수(없으면 초안 CONTACT_REQUIRED 로 막는다).
ALTER TABLE customer_order_shipping ALTER COLUMN receiver_name DROP NOT NULL;
