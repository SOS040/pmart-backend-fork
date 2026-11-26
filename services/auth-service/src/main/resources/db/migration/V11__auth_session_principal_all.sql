-- 고객 최근 활동(2026-11-26 CU-6): 폐기된 세션까지 계정별 마지막 갱신 시각을 찾는다. 기존 ix_auth_session_principal 은 ACTIVE 만.
CREATE INDEX ix_auth_session_principal_refreshed ON auth_session (principal_id, last_refreshed_at DESC);
