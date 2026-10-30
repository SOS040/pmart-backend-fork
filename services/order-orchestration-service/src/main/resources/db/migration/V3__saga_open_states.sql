-- 같은 초안에 새 결제 진입을 막는 상태를 넓힌다(2026-10-31, 슬라이스 5): 승인 대사 대기(RECONCILIATION_REQUIRED)·완료(COMPLETED)도
-- 하나만. 실패로 끝난 사가(COMPENSATED)만 다시 결제 진입할 수 있다.
DROP INDEX ux_checkout_saga_active_draft;
CREATE UNIQUE INDEX ux_checkout_saga_active_draft ON checkout_saga (draft_id)
    WHERE state IN ('RUNNING', 'WAITING_RESULT', 'UNKNOWN_TRACKING', 'COMPENSATING', 'RECONCILIATION_REQUIRED', 'COMPLETED');
-- 결제 사건 → 사가
CREATE INDEX ix_checkout_saga_payment_attempt ON checkout_saga ((payment ->> 'paymentAttemptId'));
