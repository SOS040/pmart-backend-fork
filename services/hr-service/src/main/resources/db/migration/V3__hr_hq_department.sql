-- 본사 부서 코드 목록(2026-11-15 사용자 요청 — HR 백오피스가 직접 입력하던 부서 코드를 고르게).
-- 소속(HQ_DEPARTMENT) 코드는 지금처럼 아무 값이나 받고, 처음 보는 코드는 등록·소속 변경 트랜잭션에서 이 표에 더한다(거절하지 않는다).
-- 이름은 인사팀이 PUT /hr/departments/{code} 로 붙인다(없으면 NULL — 화면은 코드만). 적용된 파일은 수정하지 않는다.
CREATE TABLE hq_department (
    code                varchar(64)   NOT NULL,          -- Affiliation.code 와 같은 형식 ^[A-Za-z0-9_.-]{1,64}$
    name                varchar(60),                     -- 표시 이름(모르면 NULL)
    updated_by          varchar(64)   NOT NULL,          -- 운영자 ID 또는 system
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    CONSTRAINT pk_hq_department PRIMARY KEY (code)
);

-- 이미 입력된 값으로 채운다: 지금 소속이 본사 부서인 직원의 코드(이름 모름 = NULL).
-- 변경 기록(employee_change AFFILIATION_CHANGED)의 detail 은 코드만 있고 소속 종류가 없어 센터 코드와 가릴 수 없으므로 쓰지 않는다.
INSERT INTO hq_department (code, name, updated_by, created_at, updated_at)
SELECT DISTINCT affiliation_code, NULL, 'system', now(), now()
  FROM employee
 WHERE affiliation_type = 'HQ_DEPARTMENT'
ON CONFLICT (code) DO NOTHING;
