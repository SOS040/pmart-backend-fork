-- 직원용 기간 환불 집계(2026-11-18, CL-3 — 백오피스 대시보드 D2, 계약 0.5.0). 완료 환불을 완료 시각 범위로 읽는다.
-- 읽기 전용 조회의 색인만 더한다(표·열 변경 없음).
CREATE INDEX ix_refund_intent_completed ON refund_intent (completed_at) WHERE state = 'COMPLETED';
