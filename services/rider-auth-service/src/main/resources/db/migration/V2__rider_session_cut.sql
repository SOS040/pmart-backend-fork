-- 정지·계약 종료 라이더 로그인 끊기(2026-11-15, 사용자 "추천대로" — 진행 중 배송이 끝난 뒤 끊기, ADR-0054 ⑤ 라이더 정지).
-- rider-service RiderRestricted(delivery.rider.v1) 의 SUSPENDED·TERMINATED 사본. PENDING = dispatch 에 맡은 배송이 남아 기다림,
-- DONE = 모든 세션을 끊음(그 뒤 제한이 유효한 동안 로그인 거절), LIFTED = 풀림·신규배차 제한으로 바뀜·기한 지남(버전만 남겨 늦게 온 옛 사건을 거른다).
CREATE TABLE rider_session_cut (
    rider_id           varchar(40)  NOT NULL,
    restriction        varchar(24)  NOT NULL,
    restriction_until  timestamptz,
    rider_version      bigint       NOT NULL,          -- RiderRestricted aggregateVersion(라이더 행 버전)
    state              varchar(16)  NOT NULL,
    requested_at       timestamptz  NOT NULL,
    cut_at             timestamptz,
    last_checked_at    timestamptz,
    CONSTRAINT pk_rider_session_cut PRIMARY KEY (rider_id),
    CONSTRAINT ck_rider_session_cut_state CHECK (state IN ('PENDING', 'DONE', 'LIFTED'))
);
CREATE INDEX ix_rider_session_cut_pending ON rider_session_cut (requested_at) WHERE state = 'PENDING';
