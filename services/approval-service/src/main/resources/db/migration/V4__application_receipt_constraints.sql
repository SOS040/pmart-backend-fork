-- 반영 회신 표 제약 보강(2026-11-06, 정규화 점검). V3 는 적용된 파일이라 고치지 않는다.
-- 1) event_id 는 후보키다 — 회신 이벤트 하나에 회신 행 하나. 지금은 inbox 와 (approval_id, receiver_service, receipt_seq)
--    UNIQUE 가 중복을 막지만 키로 선언해 둔다. 같은 event_id 가 다른 순번으로 오면(발행 측 오류) 저장이 실패해
--    재시도 뒤 DLT·dead_letter 로 드러난다(조용히 버리지 않음).
-- 2) result 는 계약(ApplicationReceiptRecorded.v1 의 result enum)과 ApprovalRules.RECEIPT_RESULTS 의 다섯 값만.
--    벗어난 값도 같은 방식으로 DLT 로 간다.

ALTER TABLE application_receipt
    ADD CONSTRAINT ux_receipt_event UNIQUE (event_id);

ALTER TABLE application_receipt
    ADD CONSTRAINT ck_receipt_result
        CHECK (result IN ('ACCEPTED', 'APPLIED', 'PARTIAL', 'REJECTED', 'UNKNOWN_RESULT'));
