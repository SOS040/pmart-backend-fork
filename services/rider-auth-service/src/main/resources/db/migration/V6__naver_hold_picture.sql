-- 네이버 프로필 사진 주소 잠시 보관(2026-11-25 — 사용자 "프로필 사진도 해줘"). 적용된 파일은 수정하지 않는다.
-- 코드 교환 때 프로필 API response.profile_image(https·*.pstatic.net 만)를 이름·별명과 같은 행에 10분 → 로그인 때 RiderAuthenticated.pictureUrl.
ALTER TABLE naver_email_hold ADD COLUMN picture varchar(1000);
