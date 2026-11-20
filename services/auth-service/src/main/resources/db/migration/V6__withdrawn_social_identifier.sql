-- 고객 탈퇴(2026-11-20 ADR-0077 결정 5 — 사용자 "마스터/활성 유저 테이블과 완전 분리해서 별도 테이블에 격리 보관").
-- 적용된 파일은 수정하지 않는다.

-- 탈퇴한 소셜 계정 식별값(보관 전용 격리 표). 회원 표(customer_account·social_identity)와 외래키·조인·계정 ID 가 없다 —
-- 원래 sub 도 남기지 않고 SHA-256("provider:subject") hex 만 둔다.
-- 읽어도 되는 곳은 첫 구매 혜택 중복 판정 하나뿐이다(이번 범위는 저장·만료 파기만). 로그인·계정 조회 등 다른 기능에서 읽지 않는다.
-- 만료(expires_at = 탈퇴 완료 + 5년)가 지난 행은 주기 작업(WithdrawnIdentifierPurger)이 지운다.
CREATE TABLE withdrawn_social_identifier (
    identifier_hash     char(64)     NOT NULL,          -- SHA-256 hex of "provider:subject"
    provider            varchar(16)  NOT NULL,          -- GOOGLE / KAKAO / NAVER / APPLE
    withdrawn_at        timestamptz  NOT NULL,          -- 탈퇴 완료 시각(같은 소셜이 다시 가입·탈퇴하면 가장 최근 것)
    expires_at          timestamptz  NOT NULL,          -- withdrawn_at + 5년, 지나면 파기
    CONSTRAINT pk_withdrawn_social_identifier PRIMARY KEY (identifier_hash)
);
CREATE INDEX ix_withdrawn_social_identifier_expires ON withdrawn_social_identifier (expires_at);
COMMENT ON TABLE withdrawn_social_identifier IS
    'ADR-0077 격리 보관 — 첫 구매 혜택 중복 판정만 읽는다. 회원 표와 외래키·조인 없음, 만료 행은 주기 파기';
