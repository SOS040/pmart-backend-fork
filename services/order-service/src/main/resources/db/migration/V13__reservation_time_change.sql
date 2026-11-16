-- 예약배달 시간 변경 요청(S4, 2026-11-16 — OMS §4). 고객 요청 → PENDING(orchestration S4 사가 시작) → 사가 결과 CONFIRMED / REJECTED.
-- 주문당 진행 중(PENDING) 하나. 성공하면 customer_order 의 도착 시간대·버전이 바뀐다.
CREATE TABLE order_time_change (
    request_id      VARCHAR(64)  PRIMARY KEY,
    order_id        VARCHAR(64)  NOT NULL,
    account_id      VARCHAR(64)  NOT NULL,
    from_date       DATE         NOT NULL,
    from_start      VARCHAR(5)   NOT NULL,
    from_end        VARCHAR(5)   NOT NULL,
    to_date         DATE         NOT NULL,
    to_start        VARCHAR(5)   NOT NULL,
    to_end          VARCHAR(5)   NOT NULL,
    state           VARCHAR(12)  NOT NULL CHECK (state IN ('PENDING', 'CONFIRMED', 'REJECTED', 'UNKNOWN')),
    reason          VARCHAR(64),
    saga_id         VARCHAR(64),
    requested_at    TIMESTAMPTZ  NOT NULL,
    decided_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_order_time_change_pending ON order_time_change (order_id) WHERE state = 'PENDING';
