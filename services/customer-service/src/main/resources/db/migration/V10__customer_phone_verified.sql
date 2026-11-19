-- 휴대폰 번호 저장(2026-11-19, 클라이언트 BR-32 — 사용자 "본인확인 보류하고 저장"). customer.phone(V4)은 있었고 쓰지 않았다.
-- 확인 여부를 따로 둔다 — 본인 확인 방식이 생기기 전까지 저장하는 번호는 모두 false(확인 안 된 연락처). 적용된 파일은 수정하지 않는다.
ALTER TABLE customer ADD COLUMN phone_verified boolean NOT NULL DEFAULT false;
