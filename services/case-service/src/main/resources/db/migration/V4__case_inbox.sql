-- 사건함(2026-11-16, 계약 0.4.0 — 사용자 결정 "사건함 공통 결정": 유형별 팀이 공용 목록에서 집어 간다, 처리 기한은 표시만).
-- 같은 case_record 표를 쓴다(고객 문의 CS_INQUIRY 도 이 표 — 목록 /cases 에서는 뺀다). 개인정보 원문은 넣지 않는다(참조·요약만).
ALTER TABLE case_record ADD COLUMN priority          varchar(4);
ALTER TABLE case_record ADD COLUMN subject_type      varchar(20);
ALTER TABLE case_record ADD COLUMN center_id         varchar(64);
ALTER TABLE case_record ADD COLUMN assignee_id       varchar(128);
ALTER TABLE case_record ADD COLUMN assignee_group    varchar(20);
ALTER TABLE case_record ADD COLUMN sla_due_at        timestamptz;
ALTER TABLE case_record ADD COLUMN summary           varchar(2000);
ALTER TABLE case_record ADD COLUMN refs              jsonb        NOT NULL DEFAULT '[]';
ALTER TABLE case_record ADD COLUMN source_event_id   varchar(64);
ALTER TABLE case_record ADD COLUMN source_event_type varchar(64);
ALTER TABLE case_record ADD COLUMN dedupe_key        varchar(200);  -- 같은 일로 열린 사건 하나만(도움요청 incidentId, 위치 끊김 라이더+배송, 오배송 배송)
ALTER TABLE case_record ADD COLUMN version           integer      NOT NULL DEFAULT 0;
ALTER TABLE case_record ADD COLUMN updated_at        timestamptz;
ALTER TABLE case_record ADD COLUMN closed_at         timestamptz;
ALTER TABLE case_record ADD COLUMN closed_by         varchar(128);
ALTER TABLE case_record ADD COLUMN resolution        varchar(30);
ALTER TABLE case_record ADD COLUMN closure_summary   varchar(2000);
UPDATE case_record SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE case_record ALTER COLUMN updated_at SET DEFAULT now();  -- 고객 문의(InquiryStore)는 이 칸 없이 넣는다
ALTER TABLE case_record ALTER COLUMN updated_at SET NOT NULL;

CREATE UNIQUE INDEX ux_case_record_source_event ON case_record (source_event_id) WHERE source_event_id IS NOT NULL;
CREATE UNIQUE INDEX ux_case_record_open_dedupe ON case_record (dedupe_key) WHERE dedupe_key IS NOT NULL AND state <> 'CLOSED';
CREATE INDEX ix_case_record_inbox ON case_record (state, case_type, priority, sla_due_at);

-- 사건 이력(누가·언제·무엇) — 추가만
CREATE TABLE case_history (
    history_id  bigserial     NOT NULL,
    case_id     varchar(64)   NOT NULL,
    at          timestamptz   NOT NULL,
    actor_id    varchar(128)  NOT NULL,
    action      varchar(30)   NOT NULL,   -- OPENED / ASSIGNED / CLOSED / RECURRED
    from_state  varchar(20),
    to_state    varchar(20),
    note        varchar(500),
    CONSTRAINT pk_case_history PRIMARY KEY (history_id),
    CONSTRAINT fk_case_history_case FOREIGN KEY (case_id) REFERENCES case_record (case_id)
);
CREATE INDEX ix_case_history_case ON case_history (case_id, at);
