-- 쿠폰 점유 추적(2026-11-21 ADR-0082). 결제 진입 때 promotion 에 쿠폰을 점유(점유 키 = 초안 ID — 같은 초안의 다시 결제는 같은 점유)하고,
-- 주기 작업(CouponHoldSync)이 주문 확정 → 사용 확정, 결제 중단(사가 종료·주문 없음) → 해제, 주문 전체 취소 → 복원을 맞춘다.
CREATE TABLE order_coupon_hold (
    draft_id        varchar(64)  NOT NULL,
    account_id      varchar(64)  NOT NULL,
    coupon_id       varchar(64)  NOT NULL,
    hold_id         varchar(64)  NOT NULL,
    discount_amount bigint       NOT NULL,
    state           varchar(12)  NOT NULL,      -- HELD / CONFIRMED / RELEASED / RESTORED (promotion 점유 상태 사본)
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT pk_order_coupon_hold PRIMARY KEY (draft_id),
    CONSTRAINT ck_order_coupon_hold_state CHECK (state IN ('HELD', 'CONFIRMED', 'RELEASED', 'RESTORED'))
);
CREATE INDEX ix_order_coupon_hold_open ON order_coupon_hold (state) WHERE state IN ('HELD', 'CONFIRMED');
