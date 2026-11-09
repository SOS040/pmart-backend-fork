-- 고객센터 문의(2026-11-09, 계약 0.2.0, 사용자 결정 C4): 고객앱 "고객센터 문의" 페이지·고객 RAG 연결.
-- 문의 하나 = CS 접수(channel APP) + 사건(CS_INQUIRY). 사건 표는 지금 문의만 쓴다(다른 사건 유형은 나중에 같은 표로).
-- 다른 서비스 테이블 참조 없음 — order_id 는 order-service 의 ID 사본(본인 주문 확인은 API 로).
CREATE TABLE case_record (
    case_id      varchar(64)  NOT NULL,
    case_type    varchar(30)  NOT NULL,
    state        varchar(20)  NOT NULL,
    subject_ref  varchar(64),
    created_at   timestamptz  NOT NULL,
    CONSTRAINT pk_case_record PRIMARY KEY (case_id)
);

CREATE TABLE cs_inquiry (
    inquiry_id   varchar(64)   NOT NULL,
    case_id      varchar(64)   NOT NULL,
    customer_id  varchar(64)   NOT NULL,
    category     varchar(20)   NOT NULL,
    order_id     varchar(64),
    title        varchar(100),
    body         text          NOT NULL,
    origin       varchar(30)   NOT NULL,
    status       varchar(20)   NOT NULL,
    created_at   timestamptz   NOT NULL,
    answered_at  timestamptz,
    version      integer       NOT NULL DEFAULT 0,
    CONSTRAINT pk_cs_inquiry PRIMARY KEY (inquiry_id),
    CONSTRAINT fk_cs_inquiry_case FOREIGN KEY (case_id) REFERENCES case_record (case_id)
);
CREATE INDEX ix_cs_inquiry_customer ON cs_inquiry (customer_id, created_at DESC, inquiry_id DESC);
CREATE INDEX ix_cs_inquiry_status ON cs_inquiry (status, created_at DESC, inquiry_id DESC);

CREATE TABLE cs_inquiry_reply (
    reply_id     varchar(64)  NOT NULL,
    inquiry_id   varchar(64)  NOT NULL,
    author_type  varchar(10)  NOT NULL,
    author_id    varchar(64)  NOT NULL,
    body         text         NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT pk_cs_inquiry_reply PRIMARY KEY (reply_id),
    CONSTRAINT fk_cs_inquiry_reply FOREIGN KEY (inquiry_id) REFERENCES cs_inquiry (inquiry_id)
);
CREATE INDEX ix_cs_inquiry_reply ON cs_inquiry_reply (inquiry_id, created_at);
