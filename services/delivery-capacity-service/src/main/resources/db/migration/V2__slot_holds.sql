-- 슬롯 점유(HoldSlot, 자원 확보 사가 S1 d단계) — delivery-capacity-service.yaml /internal/slot-holds, ADR-0008.
-- HELD → CONFIRMED / RELEASED / EXPIRED. 점유 수(held·confirmed)는 이 표에서 센다(별도 집계 열을 두지 않아 어긋날 일이 없다).
-- 적용된 파일은 수정하지 않는다. 변경은 V3 이후로.

-- ---------- 슬롯 점유 ----------
CREATE TABLE slot_hold (
    hold_id             varchar(64)  NOT NULL,
    version             integer      NOT NULL,          -- 상태 전이마다 +1 (X-Expected-Version 비교 대상)
    order_id            varchar(128) NOT NULL,
    draft_id            varchar(128),
    saga_id             varchar(128),
    mfc_id              varchar(64)  NOT NULL,
    slot_date           date         NOT NULL,          -- Asia/Seoul 날짜
    start_minute        integer      NOT NULL,          -- 도착 시간대 시작(분, 0~1439)
    end_minute          integer      NOT NULL,          -- 끝(분, 24:00 = 1440)
    state               varchar(16)  NOT NULL,
    held_at             timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,          -- HELD 만료 시각(TTL). 지나면 정원에서 빠지고 정리 작업이 EXPIRED 로 바꾼다
    confirmed_at        timestamptz,
    released_at         timestamptz,
    release_reason      varchar(32),
    approval_ref        varchar(128),                   -- OPERATOR 해제 승인 근거
    source_system       varchar(64)  NOT NULL,          -- 점유를 요청한 서비스(X-Source-System)
    CONSTRAINT pk_slot_hold PRIMARY KEY (hold_id),
    CONSTRAINT ck_slot_hold_state CHECK (state IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED'))
);
-- 주문당 활성 점유 1건(분할배송도 1건). 동시에 들어온 두 번째 점유는 이 색인에서 막힌다.
CREATE UNIQUE INDEX ux_slot_hold_active_order ON slot_hold (order_id) WHERE state IN ('HELD', 'CONFIRMED');
-- 슬롯별 점유 수 집계
CREATE INDEX ix_slot_hold_slot ON slot_hold (mfc_id, slot_date, start_minute) WHERE state IN ('HELD', 'CONFIRMED');
-- 만료 정리
CREATE INDEX ix_slot_hold_expiry ON slot_hold (expires_at) WHERE state = 'HELD';

-- ---------- 슬롯 잠금 행 ----------
-- 같은 (센터, 날짜, 시작) 점유를 직렬화한다(SELECT ... FOR UPDATE). 마지막 정원 1자리에 동시 점유 2건 → 1건만 성공.
CREATE TABLE slot_lock (
    mfc_id              varchar(64)  NOT NULL,
    slot_date           date         NOT NULL,
    start_minute        integer      NOT NULL,
    CONSTRAINT pk_slot_lock PRIMARY KEY (mfc_id, slot_date, start_minute)
);

-- ---------- 확정·해제 명령 결과 (GET .../confirmations/{requestId}, .../releases/{requestId}) ----------
CREATE TABLE slot_hold_command (
    hold_id             varchar(64)  NOT NULL,
    kind                varchar(16)  NOT NULL,          -- CONFIRM / RELEASE
    request_id          varchar(128) NOT NULL,          -- Idempotency-Key
    status              varchar(16)  NOT NULL,          -- CommandStatus
    resulting_state     varchar(16)  NOT NULL,
    version             integer      NOT NULL,
    occurred_at         timestamptz  NOT NULL,
    CONSTRAINT pk_slot_hold_command PRIMARY KEY (hold_id, kind, request_id),
    CONSTRAINT ck_slot_hold_command_kind CHECK (kind IN ('CONFIRM', 'RELEASE'))
);
