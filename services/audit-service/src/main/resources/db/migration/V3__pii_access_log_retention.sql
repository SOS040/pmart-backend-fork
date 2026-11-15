-- 개인정보 열람 기록 보존 2년 뒤 파기(2026-11-15 사용자 "너추천대로 할게", ADR-0062 결정 9).
-- 추가 전용 트리거는 그대로 두되, 정리 작업(PiiAccessLogPurger)이 같은 트랜잭션에서 pmart.audit_purge = on 을 켰을 때의 DELETE 만 허용한다.
-- UPDATE 는 언제나 거부. 화면·API 로 지우는 길은 없다.
CREATE OR REPLACE FUNCTION pii_access_log_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('pmart.audit_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'pii_access_log 는 추가만 허용합니다(%)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE INDEX ix_pii_access_log_occurred ON pii_access_log (occurred_at);
