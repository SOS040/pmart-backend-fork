-- 운영자 수동 개입(BO-12, 2026-11-16 사용자 결정 — 계약 0.2.0): 조치 기록과 "처리 완료" 표시.
-- 사가 표의 상태·자원은 바꾸지 않는다(처리 완료 = 사람이 밖에서 해결했다는 기록, 멈춘 사가 목록에서만 빠진다).
CREATE TABLE saga_manual_action (
    request_id      varchar(128)  NOT NULL,        -- 조치 요청 ID(호출자 Idempotency-Key)
    saga_id         varchar(64)   NOT NULL,
    action          varchar(32)   NOT NULL,        -- REQUERY_RESULT / MARK_RESOLVED
    operator_id     varchar(128)  NOT NULL,
    reason          varchar(500)  NOT NULL,
    state           varchar(16)   NOT NULL,        -- SUCCEEDED / FAILED
    result_summary  varchar(500),
    requested_at    timestamptz   NOT NULL,
    finished_at     timestamptz,
    CONSTRAINT pk_saga_manual_action PRIMARY KEY (saga_id, request_id)
);

CREATE TABLE saga_manual_resolution (
    saga_id         varchar(64)   NOT NULL,
    resolved_by     varchar(128)  NOT NULL,
    reason          varchar(500)  NOT NULL,
    resolved_at     timestamptz   NOT NULL,
    request_id      varchar(128)  NOT NULL,
    CONSTRAINT pk_saga_manual_resolution PRIMARY KEY (saga_id)
);
