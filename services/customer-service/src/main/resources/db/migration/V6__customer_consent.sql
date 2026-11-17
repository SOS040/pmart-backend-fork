-- 약관 동의(2026-11-17, CU-1 사용자 결정 "추천대로"). 필수 3(TERMS_OF_SERVICE·PRIVACY_POLICY·AGE_OVER_14) + 선택 1(MARKETING).
-- 항목의 현행 버전은 설정(pmart.customer.consents.versions.*)이고, 여기에는 고객이 고른 값과 그 버전을 둔다.
-- 버전이 바뀌면(설정) 저장된 버전과 달라져 필수 항목은 다시 받는다(다음 로그인·주문 전). 개인정보 아님(계정 ID·항목·시각).
-- 적용된 파일은 수정하지 않는다.

-- 계정·항목별 마지막 결정(한 행). 행이 없으면 한 번도 고르지 않았다.
CREATE TABLE customer_consent (
    account_id          varchar(64)  NOT NULL,
    consent_type        varchar(40)  NOT NULL,          -- TERMS_OF_SERVICE / PRIVACY_POLICY / AGE_OVER_14 / MARKETING
    agreed              boolean      NOT NULL,
    terms_version       varchar(32)  NOT NULL,          -- 고른 약관 버전
    agreed_at           timestamptz,                    -- 마지막으로 동의한 시각(동의한 적 없으면 없음)
    withdrawn_at        timestamptz,                    -- 동의했다가 철회한 시각(지금 동의 중이면 없음)
    channel             varchar(16)  NOT NULL,          -- SIGNUP(가입) / LOGIN(로그인 재동의) / APP(앱)
    version             integer      NOT NULL,          -- 변경 차수(1부터) — CustomerConsentChanged.version
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_customer_consent PRIMARY KEY (account_id, consent_type),
    CONSTRAINT ck_customer_consent_channel CHECK (channel IN ('SIGNUP', 'LOGIN', 'APP'))
);

-- 바뀐 기록 전부(Commerce 4.1 "어떤 항목의 어떤 내용에 언제 동의·철회했는지"). 덧붙이기만 한다.
CREATE TABLE customer_consent_history (
    history_id          bigint       GENERATED ALWAYS AS IDENTITY,
    account_id          varchar(64)  NOT NULL,
    consent_type        varchar(40)  NOT NULL,
    agreed              boolean      NOT NULL,
    terms_version       varchar(32)  NOT NULL,
    channel             varchar(16)  NOT NULL,
    version             integer      NOT NULL,
    request_id          varchar(128),                   -- 바꾼 요청의 Idempotency-Key
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_customer_consent_history PRIMARY KEY (history_id)
);
CREATE INDEX ix_customer_consent_history_account ON customer_consent_history (account_id, changed_at);
