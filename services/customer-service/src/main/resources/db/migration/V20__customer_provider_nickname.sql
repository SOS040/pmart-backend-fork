-- 소셜 닉네임(2026-11-25 — 사용자 "실제 이름과 닉네임 다르게", "카카오는 닉네임값이 name 에도", "구글 닉네임도 없으면 구글이름으로").
-- name = 제공자 실명(없으면 닉네임), provider_nickname = 제공자 닉네임(없으면 이름). 고객이 직접 정한 별명은 nickname 그대로.
ALTER TABLE customer ADD COLUMN provider_nickname varchar(100);  -- 개인정보 — 로그 금지, 탈퇴 완료 때 지움
