-- 비회원 배송지는 상세 주소 없이도(2026-11-20, 계약 0.14.0 — 로그아웃 때 "지금 주소로 계속 둘러볼까요?" 로 도로명·위치만 옮긴다).
-- 회원 주소록은 서비스가 계속 필수로 검사한다(AddressService.requireDetail).
ALTER TABLE customer_address ALTER COLUMN detail_address DROP NOT NULL;
