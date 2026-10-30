-- 작업 여력 Hold (S1 c). 주문 1건 = 수용 단위 1(시험 정책 2026-10-30), 1시간 창당 수용 단위는 정책값(pmart.capacity.units-per-hour).
-- 점유 판정 직렬화: (mfc_id, window_start) 창 행을 FOR UPDATE 로 잡은 뒤 그 창의 활성 Hold 합계를 센다.
-- 잠금 순서는 항상 Hold 행 → 창 행 (교착 방지).

-- (MFC, 1시간 창) 직렬화 지점. version 은 점유가 바뀔 때마다 오른다(스냅샷 version).
CREATE TABLE capacity_window (
    mfc_id       VARCHAR(64) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL,
    version      INTEGER     NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (mfc_id, window_start)
);

CREATE TABLE capacity_hold (
    hold_id            VARCHAR(64)  PRIMARY KEY,
    order_id           VARCHAR(128) NOT NULL,
    mfc_id             VARCHAR(64)  NOT NULL,
    window_start       TIMESTAMPTZ  NOT NULL,
    window_end         TIMESTAMPTZ  NOT NULL,
    units              INTEGER      NOT NULL CHECK (units > 0),
    state              VARCHAR(16)  NOT NULL CHECK (state IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    version            INTEGER      NOT NULL,
    demand             JSONB        NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    release_reason     VARCHAR(32),
    release_source_ref VARCHAR(128),
    created_at         TIMESTAMPTZ  NOT NULL,
    confirmed_at       TIMESTAMPTZ,
    closed_at          TIMESTAMPTZ
);

-- 주문당 활성 Hold 1개
CREATE UNIQUE INDEX ux_capacity_hold_active_order ON capacity_hold (order_id)
    WHERE state IN ('HELD', 'CONFIRMED');

-- 창별 점유 합계
CREATE INDEX ix_capacity_hold_window ON capacity_hold (mfc_id, window_start)
    WHERE state IN ('HELD', 'CONFIRMED');

-- 만료 정리
CREATE INDEX ix_capacity_hold_expiry ON capacity_hold (expires_at) WHERE state = 'HELD';
