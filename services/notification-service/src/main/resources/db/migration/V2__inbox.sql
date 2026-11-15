-- 고객 알림함 1차(2026-11-16, 계약 0.2.0 — 사용자 "앱 알림함만 먼저", "이번엔 고객만").
-- 알림 한 건 = 받는 사람 한 명에게 쌓인 문구(발송 당시 사실). 같은 원사건은 한 번만(dedupe_key).
CREATE TABLE notification (
    notification_id    varchar(64)  NOT NULL,
    recipient_type     varchar(16)  NOT NULL, -- CUSTOMER (RIDER·OPERATOR 는 아직 없음)
    recipient_id       varchar(64)  NOT NULL,
    category           varchar(16)  NOT NULL,
    template_key       varchar(64)  NOT NULL,
    title              varchar(200) NOT NULL,
    body               text,
    destination_type   varchar(20),
    destination_ref    varchar(80),
    source_event_id    varchar(64),
    source_event_type  varchar(64),
    dedupe_key         varchar(160) NOT NULL,
    state              varchar(16)  NOT NULL, -- NOTICE_UNREAD / NOTICE_READ
    occurred_at        timestamptz,           -- 원사건 시각
    created_at         timestamptz  NOT NULL,
    read_at            timestamptz,
    CONSTRAINT pk_notification PRIMARY KEY (notification_id),
    CONSTRAINT uq_notification_dedupe UNIQUE (dedupe_key)
);
CREATE INDEX ix_notification_recipient ON notification (recipient_type, recipient_id, created_at DESC);
CREATE INDEX ix_notification_unread ON notification (recipient_type, recipient_id) WHERE state = 'NOTICE_UNREAD';

-- 환불 결과(RefundResult)에는 주문이 없다 → 환불 의도(RefundIntentCreated)로 주문·클레임을 잇는다.
CREATE TABLE refund_link (
    refund_intent_id   varchar(80)  NOT NULL,
    order_id           varchar(64)  NOT NULL,
    claim_id           varchar(80),
    created_at         timestamptz  NOT NULL,
    CONSTRAINT pk_refund_link PRIMARY KEY (refund_intent_id)
);

-- 의도보다 결과가 먼저 온 경우(토픽이 달라 순서가 보장되지 않는다) 결과를 잠시 둔다.
CREATE TABLE pending_refund_result (
    refund_intent_id   varchar(80)  NOT NULL,
    source_event_id    varchar(64)  NOT NULL,
    result             varchar(16)  NOT NULL,
    amount             bigint,
    result_at          timestamptz,
    created_at         timestamptz  NOT NULL,
    CONSTRAINT pk_pending_refund_result PRIMARY KEY (refund_intent_id, source_event_id)
);
