-- 권한 회수(BO-01, 2026-11-15 사용자 결정 — 회수는 승인 없이 즉시): 회수하면 valid_until = 회수 시각, 누가·왜를 남긴다(행은 지우지 않는다 — 감사 이력).
ALTER TABLE employee_grant ADD COLUMN revoked_at     timestamptz;
ALTER TABLE employee_grant ADD COLUMN revoked_by     varchar(64);
ALTER TABLE employee_grant ADD COLUMN revoke_reason  varchar(500);
ALTER TABLE employee_grant ADD COLUMN revoke_source  varchar(64);
