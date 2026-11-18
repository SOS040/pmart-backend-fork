-- 로그인 전(기기) 동의(2026-11-19, 클라이언트 요청 BR-59 처음 실행 약관 — 사용자 "응 그렇게 해줘", 계약 0.6.0).
-- 앱을 처음 켤 때 위치기반서비스 약관(LOCATION_BASED_SERVICE)·마케팅 수신(MARKETING)을 기기(X-Device-Id)별로 받는다.
-- 로그인하면 auth 가 그 기기 동의를 계정으로 옮기고(customer_consent, channel DEVICE — 계정에 현행 버전 결정이 없는 항목만) 기기 기록은 지운다.
-- 그래서 같은 기기를 다음에 비회원으로 쓰는 사람은 처음 실행 동의를 다시 받는다. 동의 이력은 계정 쪽(customer_consent_history)에 남는다.
-- 개인정보 아님(기기 ID·항목·시각). 적용된 파일은 수정하지 않는다.

CREATE TABLE customer_device_consent (
    device_id           varchar(128) NOT NULL,
    consent_type        varchar(40)  NOT NULL,          -- MARKETING / LOCATION_BASED_SERVICE
    agreed              boolean      NOT NULL,
    terms_version       varchar(32)  NOT NULL,
    agreed_at           timestamptz,
    withdrawn_at        timestamptz,
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_customer_device_consent PRIMARY KEY (device_id, consent_type)
);

-- 기기 동의를 계정으로 옮긴 기록(덧붙이기만 — 옮긴 항목 수, 0 이면 남기지 않는다)
CREATE TABLE customer_device_consent_migration (
    migration_id        bigint       GENERATED ALWAYS AS IDENTITY,
    device_id           varchar(128) NOT NULL,
    account_id          varchar(64)  NOT NULL,
    migrated_count      integer      NOT NULL,
    migrated_at         timestamptz  NOT NULL,
    CONSTRAINT pk_customer_device_consent_migration PRIMARY KEY (migration_id)
);

-- 계정 동의의 받은 곳에 DEVICE(기기 동의를 옮김) 더함
ALTER TABLE customer_consent DROP CONSTRAINT ck_customer_consent_channel;
ALTER TABLE customer_consent ADD CONSTRAINT ck_customer_consent_channel CHECK (channel IN ('SIGNUP', 'LOGIN', 'APP', 'DEVICE'));
