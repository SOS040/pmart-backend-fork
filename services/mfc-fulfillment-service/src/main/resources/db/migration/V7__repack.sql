-- 재포장(WK-07, 2026-11-08). 새 포장이 이전 포장을 대신한다(교체 관계). 이전 포장은 INVALIDATED + reject_reason 에 사유.
-- 같은 요청(재포장 Idempotency-Key)은 같은 새 포장 — package.request_id(출고 안 유일)를 그대로 쓴다.
ALTER TABLE package
    ADD COLUMN replaces_package_id varchar(64);

CREATE INDEX ix_package_replaces ON package (replaces_package_id);
