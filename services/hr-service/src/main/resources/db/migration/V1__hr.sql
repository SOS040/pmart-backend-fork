-- hr-service(ADR-0056): 직원 신원·고용 정보와 계정 발급 진행 상태. 비밀번호(Keycloak·사서함)는 받지도 저장하지도 않는다.
-- 개인정보는 이름만(연락처·주민번호 등은 받지 않는다 — 쓰는 곳이 없다, 설계 §8-1). 적용된 파일은 수정하지 않는다.

CREATE TABLE employee (
    operator_id         varchar(64)   NOT NULL,          -- OP-<UUID> — 토큰 sub·서비스 간 식별자
    employee_no         varchar(16)   NOT NULL,          -- P<연도>-<5자리>, 바뀌지 않고 다시 쓰지 않음
    name                varchar(60)   NOT NULL,
    mail_id             varchar(32)   NOT NULL,          -- <mail_id>@<도메인>. 퇴사 뒤에도 다시 쓰지 않는다(행을 지우지 않음)
    mail_address        varchar(128)  NOT NULL,
    employment_type     varchar(16)   NOT NULL,          -- REGULAR / PART_TIME / DAILY
    affiliation_type    varchar(16)   NOT NULL,          -- HQ_DEPARTMENT / CENTER
    affiliation_code    varchar(64)   NOT NULL,
    start_date          date          NOT NULL,
    end_date            date,                            -- 계약 종료일(정규직 없음) — 지나면 자동 정지
    status              varchar(16)   NOT NULL,          -- ACTIVE / SUSPENDED / LEFT
    status_reason       varchar(32),                     -- SUSPENDED·CONTRACT_ENDED·LEFT 등
    keycloak_user_id    varchar(64),                     -- 계정 발급 뒤
    iam_sync            varchar(16)   NOT NULL,          -- NONE / PENDING — 상태(활성·비활성·사서함)를 Keycloak·메일 서버에 맞추는 일이 남음
    iam_sync_attempts   integer       NOT NULL DEFAULT 0,
    iam_sync_error      varchar(64),
    iam_sync_next_at    timestamptz,
    registered_by       varchar(64)   NOT NULL,
    row_version         bigint        NOT NULL,
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    CONSTRAINT pk_employee PRIMARY KEY (operator_id),
    CONSTRAINT ux_employee_no UNIQUE (employee_no),
    CONSTRAINT ux_employee_mail_id UNIQUE (mail_id),
    CONSTRAINT ck_employee_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LEFT')),
    CONSTRAINT ck_employee_type CHECK (employment_type IN ('REGULAR', 'PART_TIME', 'DAILY')),
    CONSTRAINT ck_employee_affiliation CHECK (affiliation_type IN ('HQ_DEPARTMENT', 'CENTER')),
    CONSTRAINT ck_employee_iam_sync CHECK (iam_sync IN ('NONE', 'PENDING'))
);
CREATE INDEX ix_employee_status ON employee (status, affiliation_code);
CREATE INDEX ix_employee_end_date ON employee (end_date) WHERE status = 'ACTIVE' AND end_date IS NOT NULL;
CREATE INDEX ix_employee_iam_sync ON employee (iam_sync_next_at) WHERE iam_sync = 'PENDING';

-- 사번 순번(연도별). 원자적으로 하나씩 올린다
CREATE TABLE employee_no_counter (
    year                integer       NOT NULL,
    last_no             integer       NOT NULL,
    CONSTRAINT pk_employee_no_counter PRIMARY KEY (year)
);

-- 계정 발급 진행(직원 한 명당 한 줄). 단계별로 진행 — 재시도는 멱등(이미 된 단계는 건너뜀)
CREATE TABLE provisioning (
    operator_id         varchar(64)   NOT NULL,
    step                varchar(24)   NOT NULL,          -- REQUESTED / MAILBOX_CREATED / IAM_CREATED / INVITE_SENT / DONE / FAILED / COMPENSATED
    attempts            integer       NOT NULL,
    last_error_code     varchar(64),
    next_attempt_at     timestamptz,
    updated_at          timestamptz   NOT NULL,
    CONSTRAINT pk_provisioning PRIMARY KEY (operator_id),
    CONSTRAINT fk_provisioning_employee FOREIGN KEY (operator_id) REFERENCES employee (operator_id),
    CONSTRAINT ck_provisioning_step CHECK (step IN ('REQUESTED', 'MAILBOX_CREATED', 'IAM_CREATED', 'INVITE_SENT', 'DONE', 'FAILED', 'COMPENSATED'))
);
CREATE INDEX ix_provisioning_due ON provisioning (next_attempt_at)
    WHERE step IN ('REQUESTED', 'MAILBOX_CREATED', 'IAM_CREATED', 'INVITE_SENT');

-- 등록 화면에서 받은 요청 권한. 효력은 approval 승인 뒤 employee-auth-service(ADR-0056 결정 4) — 여기는 제출 상태만
CREATE TABLE requested_grant (
    grant_id            bigserial     NOT NULL,
    operator_id         varchar(64)   NOT NULL,
    role                varchar(64)   NOT NULL,
    centers             varchar(64)[] NOT NULL,
    valid_from          date,
    valid_to            date,
    reason              varchar(500),
    submission          varchar(16)   NOT NULL,          -- PENDING / SUBMITTED / APPROVED / REJECTED
    approval_ref        varchar(64),
    created_at          timestamptz   NOT NULL,
    CONSTRAINT pk_requested_grant PRIMARY KEY (grant_id),
    CONSTRAINT fk_requested_grant_employee FOREIGN KEY (operator_id) REFERENCES employee (operator_id),
    CONSTRAINT ck_requested_grant_submission CHECK (submission IN ('PENDING', 'SUBMITTED', 'APPROVED', 'REJECTED'))
);
CREATE INDEX ix_requested_grant_operator ON requested_grant (operator_id);

-- 인사 변경 기록(누가·무엇을·왜). 감사용
CREATE TABLE employee_change (
    change_id           bigserial     NOT NULL,
    operator_id         varchar(64)   NOT NULL,
    change_type         varchar(32)   NOT NULL,          -- REGISTERED / AFFILIATION_CHANGED / SUSPENDED / CONTRACT_ENDED / REINSTATED / LEFT / INVITE_RESENT / MAILBOX_PASSWORD_RESET / PROVISIONING_RETRIED
    changed_by          varchar(64)   NOT NULL,          -- 운영자 ID 또는 system
    source_system       varchar(64)   NOT NULL,
    reason              varchar(500),
    detail              jsonb,
    changed_at          timestamptz   NOT NULL,
    CONSTRAINT pk_employee_change PRIMARY KEY (change_id)
);
CREATE INDEX ix_employee_change_operator ON employee_change (operator_id, changed_at);
