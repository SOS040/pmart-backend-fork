-- PMart 공통: 요청 멱등 기록 (common-messaging RequestIdempotency, ADR-0009 결정 5 "요청" 계층).
-- (source_system, request_id) = (X-Source-System, Idempotency-Key). 같은 키·같은 본문은 저장된 결과를 재생, 다른 본문·다른 행위자·다른 작업은 IDEMPOTENCY_CONFLICT.
-- 행은 업무 변경과 같은 로컬 트랜잭션에서 쓴다. 롤백되면 기록도 없다(예외로 끝난 요청은 같은 키로 다시 처리된다).
-- 이미 적용된 파일은 수정하지 않는다. 변경은 V0_0_3 이후로.
CREATE TABLE IF NOT EXISTS request_record (
    source_system   varchar(64)  NOT NULL,
    request_id      varchar(128) NOT NULL,
    operation       varchar(96)  NOT NULL,                 -- 작업 이름(operationId 등). 같은 키를 다른 작업에 쓰면 충돌
    actor           varchar(128) NOT NULL,                 -- 호출 주체(JWT subject, 없으면 'anonymous'). 다른 주체의 키 재사용은 충돌(결과 노출 방지)
    request_hash    char(64)     NOT NULL,                 -- 정규화한 요청 본문의 SHA-256 (16진수)
    result          jsonb        NOT NULL,                 -- 반환한 결과(재생용)
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_request_record PRIMARY KEY (source_system, request_id)
);
-- 보존 기간·정리 주기는 운영 승인값 [결정 필요] — 정리는 created_at 기준으로 한다.
CREATE INDEX IF NOT EXISTS ix_request_record_created_at ON request_record (created_at);
