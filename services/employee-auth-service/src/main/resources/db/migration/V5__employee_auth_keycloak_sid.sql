-- 로그아웃 전파(ADR-0061, 2026-11-14 사용자 결정): 같은 Keycloak 세션(같은 브라우저 로그인)으로 열린 SSO 앱 세션을 함께 끝내고,
-- Keycloak 백채널 로그아웃(logout_token 의 sid)으로도 끝내려고 SSO 앱 세션에 Keycloak 세션 ID(ID 토큰 sid)를 둔다.
-- sid 는 자격 증명이 아니다(그것만으로 로그인할 수 없다). SSO 가 아닌 앱(PDA)은 비운다 — 로그인 직후 Keycloak 세션을 끝내므로 묶을 것이 없다.
ALTER TABLE login_ticket ADD COLUMN keycloak_sid varchar(64);
ALTER TABLE employee_session ADD COLUMN keycloak_sid varchar(64);
CREATE INDEX ix_employee_session_keycloak_sid ON employee_session (keycloak_sid)
    WHERE state = 'ACTIVE' AND keycloak_sid IS NOT NULL;
