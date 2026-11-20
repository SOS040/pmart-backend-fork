-- 채팅 세션(2026-11-20 사용자 요청 2번, ADR-0084): 한 세션에서 여러 번 묻고, 운영자가 자기 세션을 지우고, 마지막 질문 뒤 보존 기간이
-- 지나면 세션째 지운다. 답변(policy_answer)은 세션 하나에 속한다 — 세션을 지우면 그 답변도 함께 지워진다(ON DELETE CASCADE).
CREATE TABLE chat_session (
    session_id      varchar(40)   NOT NULL,
    source_system   varchar(64)   NOT NULL,
    owner_subject   varchar(128)  NOT NULL,      -- 만든 운영자 = 질문한 운영자. 본인만 보고 지운다
    created_at      timestamptz   NOT NULL,
    last_asked_at   timestamptz   NOT NULL,      -- 보존 기간 기준(마지막 질문 시각)
    CONSTRAINT pk_chat_session PRIMARY KEY (session_id)
);
CREATE INDEX ix_chat_session_owner ON chat_session (source_system, owner_subject, last_asked_at DESC, session_id DESC);
CREATE INDEX ix_chat_session_last_asked ON chat_session (last_asked_at);

ALTER TABLE policy_answer ADD COLUMN session_id varchar(40);

-- 기존 답변(세션 없음)은 답변마다 세션 하나로 옮긴다(사용자 "질문마다 세션 하나로").
INSERT INTO chat_session (session_id, source_system, owner_subject, created_at, last_asked_at)
SELECT 'SES-' || substr(answer_id, 5), source_system, asker_subject, created_at, created_at
FROM policy_answer;
UPDATE policy_answer SET session_id = 'SES-' || substr(answer_id, 5);

ALTER TABLE policy_answer ALTER COLUMN session_id SET NOT NULL;
ALTER TABLE policy_answer ADD CONSTRAINT fk_policy_answer_session
    FOREIGN KEY (session_id) REFERENCES chat_session (session_id) ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX ix_policy_answer_session ON policy_answer (session_id, created_at);
