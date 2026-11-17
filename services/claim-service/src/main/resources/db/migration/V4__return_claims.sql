-- 반품(type RETURN) — 라이더 인계 뒤 회수(CL-1, D-7·ADR-0060 결정 2, 사용자 결정 2026-11-17 "추천대로").
-- 고객센터(CS_AGENT·OPERATOR_ADMIN)가 접수 → 회수비 9,000원(회사 잘못이면 면제, 상품 환불과 따로 결제) → 회수 요청(delivery)
-- → MFC 반입·검수 → 검수 수량 원단가 환불. 흐름 정본 docs/business/flows/return-exchange-refund.md §6.
-- 상태(RETURN): FEE_PENDING(회수비 결제 대기) → IN_PROGRESS → CLOSED / RECOVERY_FAILED(회수 두 번 실패 — 상담원) / CANCELLED.

-- 클레임: 면제 사유·본인 확인 방법(값이 아니라 방법만)·연결 사건·접수 직원·반납 센터·회수 축 상태
ALTER TABLE claim ADD COLUMN fault_code      varchar(24);   -- MISDELIVERY / DEFECTIVE / DAMAGED_IN_DELIVERY / SEVERE_DELAY / WRONG_SUBSTITUTE, 없으면 고객 사유
ALTER TABLE claim ADD COLUMN identity_method varchar(24);   -- ORDER_DETAILS_MATCH / APP_SIGNED_IN_CHAT / OTHER
ALTER TABLE claim ADD COLUMN identity_note   varchar(200);
ALTER TABLE claim ADD COLUMN case_ref        varchar(64);
ALTER TABLE claim ADD COLUMN created_by      varchar(64);
ALTER TABLE claim ADD COLUMN mfc_id          varchar(32);
ALTER TABLE claim ADD COLUMN pickup_state    varchar(24);   -- REQUESTED / PICKED_UP / DELIVERED_TO_STORE / RECEIVED / INSPECTED / FAILED

-- 클레임 행: 원주문 스냅샷(단가·원수량·할인 안분)과 회수 진행 수량
ALTER TABLE claim_line ADD COLUMN unit_price     bigint;
ALTER TABLE claim_line ADD COLUMN original_qty   integer;
ALTER TABLE claim_line ADD COLUMN discount_share bigint  NOT NULL DEFAULT 0;
ALTER TABLE claim_line ADD COLUMN picked_up_qty  integer NOT NULL DEFAULT 0;
ALTER TABLE claim_line ADD COLUMN received_qty   integer NOT NULL DEFAULT 0;
ALTER TABLE claim_line ADD COLUMN resale_qty     integer NOT NULL DEFAULT 0;
ALTER TABLE claim_line ADD COLUMN dispose_qty    integer NOT NULL DEFAULT 0;
CREATE INDEX ix_claim_line_order_line ON claim_line (order_line_id);

-- 회수비(클레임당 하나). 결제 시도는 payment(주문 참조 RTF-<claimId>), 상품 환불(refund_intent)과 별개 거래.
CREATE TABLE return_fee (
    claim_id           varchar(64) NOT NULL,
    amount             bigint      NOT NULL,
    state              varchar(24) NOT NULL,   -- WAITING / PAID / UNPAID / LATE_CANCEL_PENDING / LATE_CANCELLED / LATE_CANCEL_FAILED
    payment_attempt_id varchar(64),            -- 지금 결제 시도(고객 앱 결제위젯 orderId)
    attempt_no         integer     NOT NULL DEFAULT 0,
    late_attempt_id    varchar(64),            -- 기한·취소 뒤 승인된 시도(보상 취소 대상)
    deadline_at        timestamptz NOT NULL,
    paid_at            timestamptz,
    last_error         varchar(64),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT pk_return_fee PRIMARY KEY (claim_id),
    CONSTRAINT fk_return_fee_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id)
);
CREATE INDEX ix_return_fee_open ON return_fee (updated_at)
    WHERE state IN ('WAITING', 'LATE_CANCEL_PENDING');

-- 회수비 결제 시도 이력(늦은 승인도 클레임을 찾게)
CREATE TABLE return_fee_attempt (
    payment_attempt_id varchar(64) NOT NULL,
    claim_id           varchar(64) NOT NULL,
    attempt_no         integer     NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT pk_return_fee_attempt PRIMARY KEY (payment_attempt_id),
    CONSTRAINT fk_return_fee_attempt_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id)
);

-- 회수 요청(시도마다 하나). delivery 의 회수 배송(DeliveryCreated purpose CLAIM_RETURN)과 delivery_id 로 잇는다.
CREATE TABLE return_pickup (
    pickup_request_id varchar(80) NOT NULL,   -- <claimId>-P<attempt>
    claim_id          varchar(64) NOT NULL,
    attempt_no        integer     NOT NULL,
    delivery_id       varchar(64),
    state             varchar(16) NOT NULL,   -- REQUESTED / PICKED_UP / DELIVERED / FAILED
    failure_reason    varchar(64),
    requested_by      varchar(64) NOT NULL,   -- SYSTEM 또는 상담원 ID
    requested_at      timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT pk_return_pickup PRIMARY KEY (pickup_request_id),
    CONSTRAINT fk_return_pickup_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id)
);
CREATE UNIQUE INDEX ux_return_pickup_attempt ON return_pickup (claim_id, attempt_no);
CREATE INDEX ix_return_pickup_delivery ON return_pickup (delivery_id) WHERE delivery_id IS NOT NULL;

-- 이미 반영한 MFC 사실(반입 returnReceiptId·검수 inspectionId) — 같은 사실을 두 번 세지·환불하지 않는다(inbox 와 별개로 업무 키)
CREATE TABLE return_fact (
    kind       varchar(16)  NOT NULL,   -- RECEIPT / INSPECTION
    ref        varchar(128) NOT NULL,
    claim_id   varchar(64)  NOT NULL,
    applied_at timestamptz  NOT NULL,
    CONSTRAINT pk_return_fact PRIMARY KEY (kind, ref)
);

-- 상담원 조치 기록(RETRY_PICKUP·CANCEL) — 사유(note)는 로그에 쓰지 않고 여기에만
CREATE TABLE claim_action (
    action_id  varchar(64)  NOT NULL,
    claim_id   varchar(64)  NOT NULL,
    action     varchar(24)  NOT NULL,
    actor      varchar(64)  NOT NULL,
    note       varchar(500) NOT NULL,
    acted_at   timestamptz  NOT NULL,
    CONSTRAINT pk_claim_action PRIMARY KEY (action_id),
    CONSTRAINT fk_claim_action_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id)
);
CREATE INDEX ix_claim_action_claim ON claim_action (claim_id, acted_at);

-- 환불 의도: 반품은 검수(inspectionId)마다 하나 — 클레임당 하나 제한을 (클레임, 근거) 로 바꾼다. 취소·결품은 근거 없음(클레임당 하나 그대로)
ALTER TABLE refund_intent ADD COLUMN source_ref varchar(128);
DROP INDEX ux_refund_intent_claim;
CREATE UNIQUE INDEX ux_refund_intent_claim_source ON refund_intent (claim_id, COALESCE(source_ref, ''));
