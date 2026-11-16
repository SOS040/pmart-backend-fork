-- 오배송 재발송 사가 방식(2026-11-16, ADR-0065 — 라이더·시스템 잘못 + 고객 선택). V8 은 LAN 에 이미 적용돼 새 파일로 더한다.
-- RESHIP(다시 피킹해 보냄) / REFUND(전 품목 환불 — 라이더·시스템 잘못 + 고객이 환불 선택). 옛 행은 RESHIP.
ALTER TABLE reshipment_saga ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'RESHIP';
ALTER TABLE reshipment_saga ADD CONSTRAINT ck_reshipment_saga_mode CHECK (mode IN ('RESHIP', 'REFUND'));
