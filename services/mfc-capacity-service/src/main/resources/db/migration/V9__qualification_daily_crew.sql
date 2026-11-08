-- 작업자 쪽 2단계(2026-11-08 사용자 결정): 자격과 업무 분리, 고용 형태(정규직·알바·일일크루), 일일크루 그날 근무 등록.
-- 자격 = 할 수 있는 일(감독자, 계속 유지, 만료일 선택). 업무 = 맡은 일(정규직 감독자, 일일크루 시스템·그날만). 유효 업무 = 업무 ∩ 유효 자격.
ALTER TABLE mfc_worker ADD COLUMN employment_type varchar(12) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE mfc_worker ADD CONSTRAINT ck_mfc_worker_employment CHECK (employment_type IN ('REGULAR', 'PART_TIME', 'DAILY'));
ALTER TABLE mfc_worker ADD COLUMN qualification_version integer NOT NULL DEFAULT 1;

CREATE TABLE mfc_worker_qualification (
    mfc_id                 varchar(32)  NOT NULL,
    worker_id              varchar(64)  NOT NULL,
    qualification_version  integer      NOT NULL,
    task_types             text[]       NOT NULL,
    expires_at             timestamptz,
    reason                 varchar(200),
    changed_by             varchar(64)  NOT NULL,
    changed_at             timestamptz  NOT NULL,
    CONSTRAINT pk_mfc_worker_qualification PRIMARY KEY (mfc_id, worker_id, qualification_version),
    CONSTRAINT fk_mfc_worker_qualification FOREIGN KEY (mfc_id, worker_id) REFERENCES mfc_worker (mfc_id, worker_id)
);
-- 기존 작업자: 자격 = 지금 업무 작업 유형(정규직 동작 그대로)
INSERT INTO mfc_worker_qualification (mfc_id, worker_id, qualification_version, task_types, reason, changed_by, changed_at)
SELECT w.mfc_id, w.worker_id, 1, d.task_types, '자격·업무 분리 이관(2026-11-08)', 'MIGRATION', now()
FROM mfc_worker w
JOIN mfc_worker_duty d ON d.mfc_id = w.mfc_id AND d.worker_id = w.worker_id AND d.duty_version = w.duty_version;

CREATE TABLE mfc_worker_day (
    mfc_id           varchar(32)  NOT NULL,
    worker_id        varchar(64)  NOT NULL,
    business_date    date         NOT NULL,
    duty_task_types  text[]       NOT NULL,
    enrolled_by      varchar(64)  NOT NULL,
    enrolled_at      timestamptz  NOT NULL,
    CONSTRAINT pk_mfc_worker_day PRIMARY KEY (mfc_id, worker_id, business_date),
    CONSTRAINT fk_mfc_worker_day FOREIGN KEY (mfc_id, worker_id) REFERENCES mfc_worker (mfc_id, worker_id)
);
