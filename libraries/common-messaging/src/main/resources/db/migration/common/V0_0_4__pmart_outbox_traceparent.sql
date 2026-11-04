-- PMart 공통: outbox 에 W3C traceparent(trace-correlation-plan.md 작업 B, 2026-11-04).
-- Debezium 이 Kafka 헤더 traceparent 로 싣고(additional.placement), 소비 쪽 Stream 바인더 observation 이 그것을 부모 span 으로 잇는다.
-- 봉투 계약(_envelope.v1.json)은 바꾸지 않는다 — 헤더 전용. 기존 행은 NULL(영향 없음).
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS traceparent varchar(64);
