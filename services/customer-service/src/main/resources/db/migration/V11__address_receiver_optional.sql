-- 주소의 받는 분·연락처를 선택으로(2026-11-19, 계약 0.8.0 — 클라이언트 BR-60). 주문 때 비어 있으면 프로필 값으로 채운다(내부 쇼핑 컨텍스트).
-- 이미 저장된 값은 그대로 둔다.
ALTER TABLE customer_address ALTER COLUMN receiver_name DROP NOT NULL;
ALTER TABLE customer_address ALTER COLUMN receiver_phone DROP NOT NULL;
