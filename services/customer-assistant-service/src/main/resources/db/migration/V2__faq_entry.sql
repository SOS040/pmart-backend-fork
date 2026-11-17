-- 고객앱 FAQ 목록·검색(2026-11-18 사용자 "A해줘" — 고객앱 빠진 기능 "FAQ 목록·검색", 계약 0.2.0).
-- AI 상담이 근거로 쓰는 같은 고객 안내 문서(manifest 의 domain customer-faq)를 색인할 때 "## 질문" 절마다 한 줄로 나눠 둔다.
-- 스냅숏에 묶는다 — 목록은 ACTIVE 스냅숏 것만 보여서 AI 답변의 근거와 어긋나지 않는다. 스냅숏을 지우면 같이 지워진다.
-- 이 표가 생기기 전의 스냅숏에는 행이 없다 → 재색인(lan.sh rag-ingest-customer)해야 목록이 찬다(그 전엔 422 CONFIG_REQUIRED).
CREATE TABLE faq_entry (
    snapshot_id     varchar(40)   NOT NULL REFERENCES index_snapshot (snapshot_id) ON DELETE CASCADE,
    faq_id          varchar(80)   NOT NULL,      -- <분류>-<질문 sha256 앞 10자> — 같은 질문이면 재색인해도 같은 ID
    category        varchar(40)   NOT NULL,      -- 파일 이름에서(02-cancel.md → cancel)
    category_title  varchar(200)  NOT NULL,      -- 문서 첫 제목(# …)
    category_order  integer       NOT NULL,      -- manifest 순서
    item_order      integer       NOT NULL,      -- 문서 안 절 순서
    question        varchar(500)  NOT NULL,      -- 절 제목(지위 태그 뺌)
    answer          text          NOT NULL,      -- 절 본문(마크다운, 지위 태그 뺌, 다른 안내 문서 링크는 ‘제목’으로)
    search_text     text          NOT NULL,      -- 소문자(질문 + 굵게·코드 표시 뺀 답변) — 부분 문자열 검색
    document_id     varchar(300)  NOT NULL,      -- 안내 문서 경로(인용 documentId 와 같음)
    CONSTRAINT pk_faq_entry PRIMARY KEY (snapshot_id, faq_id)
);
CREATE INDEX ix_faq_entry_order ON faq_entry (snapshot_id, category_order, item_order);
