-- 2026-11-26 사용자 "자동보충 만들어" → "추천 자동 발주 + 승인"(ADR-0091), 2026-11-27 R-27(MFC 공급 요청 승인 → DC TRF).
-- ① 자동 발주 초안: 매일 센터마다 발주 추천 수량으로 공급 요청(SUBMITTED, submitted_by SYSTEM, recommendation_ref AUTO-<센터>-<날짜>)을 만든다.
-- ② 승인 → 보급 DC 가 정해진 센터면 cdc-outbound /internal/transfer-intakes 로 이관(TRF) 요청 + 입고 예정(inbound_promise EXPECTED).
--    TRF 는 온도대마다 하나(cdc-outbound 의 TEMPERATURE_ZONE 조건이 하나뿐, 사용자 2026-11-27 PC 3 전달) — 요청 하나에 TRF 여럿.
--    호출은 트랜잭션 밖 — 결과·재시도 상태를 supply_request_transfer 에 둔다. 보급 DC 가 없으면 예전처럼 initial_stock_load.
ALTER TABLE supply_request ADD COLUMN transfer_cdc_id varchar(64);

CREATE TABLE supply_request_transfer (
    trf_id            varchar(80)  PRIMARY KEY,
    supply_request_id varchar(64)  NOT NULL REFERENCES supply_request (supply_request_id),
    seq               integer      NOT NULL,
    cdc_id            varchar(64)  NOT NULL,
    mfc_id            varchar(64)  NOT NULL,
    temperature_zone  varchar(16)  NOT NULL,
    lines             jsonb        NOT NULL,
    due_at            timestamptz  NOT NULL,
    sent_at           timestamptz,
    attempts          integer      NOT NULL DEFAULT 0,
    error             varchar(1000),
    next_try_at       timestamptz,
    created_at        timestamptz  NOT NULL,
    UNIQUE (supply_request_id, seq)
);
CREATE INDEX supply_request_transfer_due ON supply_request_transfer (next_try_at)
    WHERE sent_at IS NULL AND next_try_at IS NOT NULL;

-- 자동 초안은 센터·날짜마다 한 번
CREATE UNIQUE INDEX supply_request_auto_ref ON supply_request (recommendation_ref)
    WHERE submitted_by = 'SYSTEM';
