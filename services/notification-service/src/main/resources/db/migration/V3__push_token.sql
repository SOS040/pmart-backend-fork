-- 단말 푸시 토큰(2026-11-17, 계약 0.3.0 — 클라이언트 요청 BR-09, 사용자 승인 "백엔드 3단계").
-- 토큰만 저장한다. 발송(FCM)은 아직 없다(자격증명·발송 정책 미정). Android 만.
-- 한 줄 = 주인(고객·라이더) 한 명의 단말 하나. 토큰 하나는 한 번에 한 주인·단말에만 있다(token_sha256 유일 —
-- 토큰 원문은 4096 자까지라 원문에 유일 색인을 걸지 않는다). 토큰 원문은 로그·응답에 내보내지 않는다.
CREATE TABLE push_token (
    owner_type     varchar(16)   NOT NULL, -- CUSTOMER / RIDER
    owner_id       varchar(64)   NOT NULL,
    device_id      varchar(128)  NOT NULL,
    token          varchar(4096) NOT NULL,
    token_sha256   char(64)      NOT NULL,
    platform       varchar(16)   NOT NULL, -- ANDROID
    app_version    varchar(32),
    registered_at  timestamptz   NOT NULL, -- 이 주인·단말에 처음 등록한 시각
    updated_at     timestamptz   NOT NULL, -- 마지막 등록·교체 시각(단말 수 초과 시 가장 오래된 것부터 지운다)
    CONSTRAINT pk_push_token PRIMARY KEY (owner_type, owner_id, device_id),
    CONSTRAINT uq_push_token_token UNIQUE (token_sha256)
);
CREATE INDEX ix_push_token_owner_updated ON push_token (owner_type, owner_id, updated_at);
