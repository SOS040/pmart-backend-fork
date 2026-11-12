-- 백오피스 SSO(2026-11-12 사용자 결정): SSO 앱은 Keycloak 세션을 남기고, 로그아웃 때 브라우저가 Keycloak 로그아웃 주소로 가서 끝낸다.
-- 확인 화면 없이 끝내려면 id_token_hint 가 필요해 SSO 앱 세션에만 Keycloak ID 토큰을 둔다(클라이언트 기본 범위에서 profile·email 을 빼
-- 이름·이메일이 없다. aud = 이 서비스라 PMart API 에 쓸 수 없다). 세션이 끝나면 지운다. 복귀 주소는 로그아웃 뒤 돌아갈 출처를 정하려고.
ALTER TABLE login_ticket ADD COLUMN return_uri varchar(512);
ALTER TABLE login_ticket ADD COLUMN keycloak_id_token text;
ALTER TABLE employee_session ADD COLUMN return_uri varchar(512);
ALTER TABLE employee_session ADD COLUMN keycloak_id_token text;
