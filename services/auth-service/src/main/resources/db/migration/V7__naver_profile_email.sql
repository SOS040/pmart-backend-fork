-- 네이버 로그인 이메일(2026-11-21 BR-77). 적용된 파일은 수정하지 않는다.

-- 네이버 ID 토큰에는 이메일이 없다. 인가 코드 교환(POST /auth/naver-token-exchanges) 때 서버가 네이버 프로필 API 에서 직접 받은
-- 이메일을 그 네이버 계정(id_token sub)에 잠깐 묶어 두었다가, 같은 sub 의 검증된 id_token 으로 로그인할 때 CustomerAuthenticated
-- 에 싣고 지운다. 앱이 보낸 이메일은 받지 않는다. 원래 sub 대신 SHA-256("NAVER:" + sub) hex 만 둔다.
-- 만료(교환 + 10분)가 지난 행은 로그인 때 읽지 않고, 주기 작업(NaverProfileEmailPurger)이 지운다.
CREATE TABLE naver_profile_email (
    subject_hash   char(64)      NOT NULL,          -- SHA-256 hex of "NAVER:" + id_token sub
    email          varchar(320)  NOT NULL,          -- 네이버 프로필 API response.email(개인정보 — 로그 금지)
    expires_at     timestamptz   NOT NULL,
    CONSTRAINT pk_naver_profile_email PRIMARY KEY (subject_hash)
);
CREATE INDEX ix_naver_profile_email_expires ON naver_profile_email (expires_at);
COMMENT ON TABLE naver_profile_email IS
    'BR-77 네이버 교환 때 받은 이메일 — 같은 계정 로그인 때 한 번 쓰고 지움, 만료 10분';
