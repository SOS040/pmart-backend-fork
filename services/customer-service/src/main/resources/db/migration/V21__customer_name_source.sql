-- 고객이 직접 넣은 실명(2026-11-25 BR-107 — 사용자 "이름이랑 닉네임 내 정보에서 수정할 수 있는 기능 필요해"). 적용된 파일은 수정하지 않는다.
-- name_source PROVIDER(로그인 제공자 이름 — 로그인마다 최신) / CUSTOMER(고객이 입력 — 로그인이 덮지 않음).
-- provider_name = 제공자가 마지막으로 준 이름(되돌리기용, 개인정보 — 로그 금지, 탈퇴 완료 때 지움).
ALTER TABLE customer ADD COLUMN name_source varchar(10) NOT NULL DEFAULT 'PROVIDER';
ALTER TABLE customer ADD COLUMN provider_name varchar(100);
UPDATE customer SET provider_name = name WHERE name IS NOT NULL;
