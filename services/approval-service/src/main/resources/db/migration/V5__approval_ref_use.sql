-- 승인 번호 사용 기록(AP-1, 2026-11-17): 승인 번호(approvalRef)를 명령에 실어 집행하는 정본(MFC)이 /internal/approval-ref-uses 로
-- 승인을 한 번만 쓴다. 한 승인에 한 행(PK = approval_id) — 두 번째 사용은 INSERT ... ON CONFLICT DO NOTHING 이 막고, 같은 use_ref 면
-- 같은 결과(재시도 멱등), 다른 use_ref 면 ALREADY_USED. 쓸 수 없는 판정은 기록하지 않는다. 적용된 파일은 수정하지 않는다.

CREATE TABLE approval_ref_use (
    approval_id         varchar(40)  NOT NULL,
    use_ref             varchar(128) NOT NULL,          -- 정본이 자기 집행에서 만든 사용 멱등 키
    used_by_service     varchar(64)  NOT NULL,          -- 호출자 X-Source-System(자기 신고, ADR-0026)
    target_service      varchar(64)  NOT NULL,          -- 사용 때 확인한 정본·대상(승인 단위와 같다 — 감사용 사본)
    target_ref          varchar(128) NOT NULL,
    actor_id            varchar(128),                   -- 집행한 운영자(요청에 있었으면)
    used_at             timestamptz  NOT NULL,
    CONSTRAINT pk_approval_ref_use PRIMARY KEY (approval_id),
    CONSTRAINT fk_approval_ref_use_approval FOREIGN KEY (approval_id) REFERENCES approval (approval_id)
);
