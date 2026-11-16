-- CS 접수(2026-11-16, 계약 0.12.0 — 사용자 결정 BO-11): 상담원이 전화·채팅·메일로 받은 고객 연락 기록.
-- 접수 ≠ 명령(취소·환불 안 함). 본인 확인은 "어떻게 했는지"만 — 고객이 말한 번호·주소 등은 넣지 않는다.
-- 새 문의를 만들었으면 inquiry_id, 기존 건에 연결했으면 linked = true(case_id 가 그 건).
CREATE TABLE cs_intake (
    intake_id           varchar(64)   NOT NULL,
    case_id             varchar(64)   NOT NULL,
    inquiry_id          varchar(64),
    linked              boolean       NOT NULL,
    channel             varchar(20)   NOT NULL,
    category            varchar(20)   NOT NULL,
    customer_id         varchar(64)   NOT NULL,
    subject_ref         varchar(64),
    summary             varchar(2000) NOT NULL,
    caller_relation     varchar(10)   NOT NULL,
    identity_method     varchar(30)   NOT NULL,
    identity_note       varchar(200),
    consent_type        varchar(50),
    consent_given       boolean,
    consent_detail      varchar(500),
    consent_recorded_at timestamptz,
    received_by         varchar(128)  NOT NULL,
    received_at         timestamptz   NOT NULL,
    CONSTRAINT pk_cs_intake PRIMARY KEY (intake_id),
    CONSTRAINT fk_cs_intake_case FOREIGN KEY (case_id) REFERENCES case_record (case_id),
    CONSTRAINT fk_cs_intake_inquiry FOREIGN KEY (inquiry_id) REFERENCES cs_inquiry (inquiry_id)
);
CREATE INDEX ix_cs_intake_case ON cs_intake (case_id, received_at DESC, intake_id DESC);
CREATE INDEX ix_cs_intake_customer ON cs_intake (customer_id, received_at DESC, intake_id DESC);
CREATE INDEX ix_cs_intake_subject ON cs_intake (subject_ref, received_at DESC, intake_id DESC) WHERE subject_ref IS NOT NULL;
CREATE UNIQUE INDEX ux_cs_intake_inquiry ON cs_intake (inquiry_id) WHERE inquiry_id IS NOT NULL;
