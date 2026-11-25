-- 네이버 이름·별명 잠시 보관(2026-11-25 — 사용자 "라이더쪽에도 지금 고객앱 소셜로그인 기능 구조처럼" "이름 닉네임 먼저해줘"). 적용된 파일은 수정하지 않는다.
-- 네이버 ID 토큰에는 이름이 없어 코드 교환 때 프로필 API 의 response.name·nickname 을 가린 이메일과 같은 행에 10분 묶고, 로그인 때 꺼내
-- RiderAuthenticated 사건에 싣고 지운다(rider-auth 는 이름을 오래 두지 않는다). 이메일 없이 이름만 있을 수 있어 email_masked NULL 허용.
ALTER TABLE naver_email_hold ALTER COLUMN email_masked DROP NOT NULL;
ALTER TABLE naver_email_hold ADD COLUMN name varchar(100);      -- 개인정보 — 로그 금지
ALTER TABLE naver_email_hold ADD COLUMN nickname varchar(100);  -- 개인정보 — 로그 금지
