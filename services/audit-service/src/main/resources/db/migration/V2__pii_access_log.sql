-- 개인정보 열람·다운로드 기록(BO-25, 2026-11-15). 추가 전용 — 수정·삭제 API 없음, 아래 트리거가 UPDATE·DELETE 를 거부한다.
-- 개인정보 원문(이름·연락처·주소 값)은 넣지 않는다: subject_ref 는 계정 ID 같은 참조, fields 는 항목 코드만.
-- purpose(열람 사유, 직원 입력)는 기록 자체이므로 저장하되 로그에는 남기지 않는다. 보존기간·파기는 결정 필요(BO-25 §4) — 지금은 지우지 않는다.
CREATE TABLE pii_access_log (
    pii_access_log_id    VARCHAR(40)  PRIMARY KEY,
    sequence             BIGSERIAL    NOT NULL UNIQUE,       -- 추가 순서(단조 증가)
    source_system        VARCHAR(64)  NOT NULL,              -- 기록을 요청한 서비스(X-Source-System)
    request_id           VARCHAR(128) NOT NULL,              -- 기록 요청의 Idempotency-Key
    occurred_at          TIMESTAMPTZ  NOT NULL,              -- 열람 시각(호출자)
    recorded_at          TIMESTAMPTZ  NOT NULL,              -- 기록 시각(이 서비스)
    actor_id             VARCHAR(128) NOT NULL,
    actor_type           VARCHAR(20)  NOT NULL,
    actor_on_behalf_of   VARCHAR(128),
    actor_role_grant_ref VARCHAR(128),
    actor_roles          JSONB        NOT NULL DEFAULT '[]', -- 기록 시점 역할 사본
    actor_center_scope   JSONB        NOT NULL DEFAULT '[]',
    subject_type         VARCHAR(20)  NOT NULL,              -- CUSTOMER·RIDER·DRIVER·OPERATOR
    subject_ref          VARCHAR(128) NOT NULL,
    access_type          VARCHAR(20)  NOT NULL,              -- VIEW·DOWNLOAD·EXPORT·SEARCH
    fields               JSONB        NOT NULL,              -- 항목 코드 목록
    purpose              VARCHAR(500) NOT NULL,
    screen               VARCHAR(120),
    case_ref             VARCHAR(128),
    record_count         INTEGER      NOT NULL DEFAULT 1 CHECK (record_count >= 1),
    trace_id             VARCHAR(64),
    CONSTRAINT uq_pii_access_log_request UNIQUE (source_system, request_id)
);

CREATE INDEX ix_pii_access_log_subject ON pii_access_log (subject_type, subject_ref, occurred_at DESC);
CREATE INDEX ix_pii_access_log_actor ON pii_access_log (actor_id, occurred_at DESC);

CREATE FUNCTION pii_access_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'pii_access_log 는 추가만 허용합니다(%)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pii_access_log_append_only
    BEFORE UPDATE OR DELETE ON pii_access_log
    FOR EACH ROW EXECUTE FUNCTION pii_access_log_append_only();
