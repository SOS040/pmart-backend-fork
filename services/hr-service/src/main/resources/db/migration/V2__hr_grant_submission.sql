-- 요청 권한 제출(ADR-0056 5-4): 권한 요청 ID(GrantRequested.requestId — 한 번에 올린 행 묶음). 적용된 파일은 수정하지 않는다.
ALTER TABLE requested_grant ADD COLUMN request_id varchar(64);
ALTER TABLE requested_grant ADD COLUMN decided_at timestamptz;
CREATE INDEX ix_requested_grant_pending ON requested_grant (operator_id) WHERE submission = 'PENDING';
