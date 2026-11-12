-- hr-service 직원 사건 반영(ADR-0056 5-3): 근무 시작일(그 전에는 로그인 불가)과 마지막으로 반영한 직원 사건 버전(늦게 온 옛 사건을 버린다).
ALTER TABLE employee_account ADD COLUMN start_date date;
ALTER TABLE employee_account ADD COLUMN hr_version bigint;
