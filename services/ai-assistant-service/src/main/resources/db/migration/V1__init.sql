-- ai-assistant-service 1차 저장소 (ADR-0036). 이 서비스가 소유한다 — 문서 사본 색인과 답변 기록. 다른 서비스 DB 와 무관.
-- 적용된 파일은 수정하지 않는다. 변경은 V2 이후로.

CREATE EXTENSION IF NOT EXISTS vector;   -- 임베딩 검색(코사인)
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- 키워드 보정(word_similarity)

-- 색인 스냅숏: 모델·차원·전처리가 스냅숏마다 고정 → 다른 모델의 벡터가 한 검색에 섞이지 않는다. ACTIVE 는 하나뿐.
CREATE TABLE index_snapshot (
    snapshot_id              varchar(40)   NOT NULL,
    embedding_model          varchar(100)  NOT NULL,
    embedding_model_version  varchar(50)   NOT NULL,
    embedding_dim            integer       NOT NULL,
    preprocessing_hash       char(64)      NOT NULL,
    manifest_hash            char(64)      NOT NULL,
    source_revision          varchar(64),                 -- git rev-parse HEAD
    source_dirty             boolean       NOT NULL,      -- 작업 트리에 커밋 안 된 변경 → index_source_file 해시로 식별
    state                    varchar(12)   NOT NULL,      -- BUILDING / ACTIVE / RETIRED / FAILED
    chunk_count              integer       NOT NULL DEFAULT 0,
    failure_reason           varchar(500),
    created_at               timestamptz   NOT NULL,
    activated_at             timestamptz,
    retired_at               timestamptz,
    CONSTRAINT pk_index_snapshot PRIMARY KEY (snapshot_id)
);
CREATE UNIQUE INDEX uq_index_snapshot_active ON index_snapshot ((true)) WHERE state = 'ACTIVE';

-- 실제로 읽은 파일(청크를 만든 그 바이트의 sha256). HEAD 만으로 재현되지 않는 입력을 식별한다.
CREATE TABLE index_source_file (
    snapshot_id  varchar(40)  NOT NULL REFERENCES index_snapshot (snapshot_id) ON DELETE CASCADE,
    path         varchar(300) NOT NULL,
    sha256       char(64)     NOT NULL,
    bytes        bigint       NOT NULL,
    CONSTRAINT pk_index_source_file PRIMARY KEY (snapshot_id, path)
);

-- 문서 청크. 정책 지위(authority)·생애(lifecycle_status)·구현 기록(impl_record_date)은 서로 다른 축.
CREATE TABLE doc_chunk (
    snapshot_id             varchar(40)    NOT NULL REFERENCES index_snapshot (snapshot_id) ON DELETE CASCADE,
    chunk_id                char(64)       NOT NULL,      -- sha256(path + section + 정규화 본문) — 재색인 멱등
    document_id             varchar(300)   NOT NULL,
    path                    varchar(300)   NOT NULL,
    section_path            varchar(1000),
    domain_area             varchar(50),
    authority               varchar(30)    NOT NULL,      -- CONFIRMED / ADOPTED_DESIGN / PROPOSAL / ASSUMPTION / UNDECIDED / EXTERNAL_REFERENCE
    lifecycle_status        varchar(30)    NOT NULL,      -- ACTIVE / SUPERSEDED / SUPERSESSION_PROPOSED / EXCLUDED_FROM_PHASE1
    superseded_by           varchar(300),                 -- 활성 대체의 근거 문서
    proposed_superseded_by  text[]         NOT NULL DEFAULT '{}', -- 대체를 제안한(승인 안 된) 문서
    precedence_rank         integer        NOT NULL,      -- domain-map.md:35 우선순위 등급(클수록 우선)
    impl_record_date        varchar(20),                  -- 날짜가 붙은 구현 기록 절의 기준일(현재 구현 보장 아님)
    impl_note               varchar(500),
    document_version        varchar(50),                  -- 원문에 있을 때만
    effective_date          varchar(50),                  -- 원문에 있을 때만
    stage                   varchar(30),
    access_scope            varchar(50)    NOT NULL,
    content                 text           NOT NULL,
    content_hash            char(64)       NOT NULL,
    embedding               vector         NOT NULL,
    CONSTRAINT pk_doc_chunk PRIMARY KEY (snapshot_id, chunk_id)
);
CREATE INDEX ix_doc_chunk_doc ON doc_chunk (snapshot_id, document_id);
CREATE INDEX ix_doc_chunk_trgm ON doc_chunk USING gin (content gin_trgm_ops);

-- 임베딩 캐시: 모델 이름·버전·차원·전처리 설정이 모두 같을 때만 재사용한다.
CREATE TABLE embedding_cache (
    model               varchar(100)  NOT NULL,
    model_version       varchar(50)   NOT NULL,
    dim                 integer       NOT NULL,
    preprocessing_hash  char(64)      NOT NULL,
    content_hash        char(64)      NOT NULL,
    embedding           vector        NOT NULL,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_embedding_cache PRIMARY KEY (model, model_version, dim, preprocessing_hash, content_hash)
);

-- 답변 기록 = 요청 선점·처리 중·완료(공통 request_record 를 쓰지 않는다 — 모델 호출을 긴 트랜잭션 밖에 두기 위해).
-- 범위 (source_system, asker_subject, request_id). 질문 원문은 기본 저장 안 함(request_hash 만).
CREATE TABLE policy_answer (
    answer_id        varchar(40)   NOT NULL,
    source_system    varchar(64)   NOT NULL,
    asker_subject    varchar(128)  NOT NULL,
    request_id       varchar(128)  NOT NULL,
    request_hash     char(64)      NOT NULL,
    state            varchar(20)   NOT NULL,      -- PROCESSING / COMPLETED / FAILED_RETRYABLE
    lease_until      timestamptz,
    attempt          integer       NOT NULL,
    last_error_code  varchar(40),                 -- CONFIG_REQUIRED(422) / DEPENDENCY_UNAVAILABLE(503)
    result_state     varchar(30),                 -- ANSWERED / NO_GROUNDING / REALTIME_DATA_REQUIRED / OUT_OF_SCOPE / FORBIDDEN_SCOPE
    answer           text,
    citations        jsonb,                       -- 인용(청크별 access_scope 포함 — 스냅숏 삭제 뒤에도 권한 재검사)
    snapshot_id      varchar(40),                 -- FK 없음: 완료 답변은 스냅숏 정리 뒤에도 남는다
    answer_mode      varchar(30),
    retrieval_mode   varchar(30),
    chat_model       varchar(100),
    embedding_model  varchar(150),
    question         text,                        -- pmart.assistant.answers.store-question=true 일 때만
    created_at       timestamptz   NOT NULL,
    completed_at     timestamptz,
    CONSTRAINT pk_policy_answer PRIMARY KEY (answer_id),
    CONSTRAINT uq_policy_answer_request UNIQUE (source_system, asker_subject, request_id)
);
CREATE INDEX ix_policy_answer_processing ON policy_answer (snapshot_id) WHERE state = 'PROCESSING';
CREATE INDEX ix_policy_answer_created ON policy_answer (created_at);
