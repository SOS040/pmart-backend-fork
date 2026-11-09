-- 작업자 속도 배율(2026-11-09 사용자 결정, 계약 0.11.0): 배정 추천에서 그 작업자의 작업 시간 = 작업 추정 분 ÷ 배율(1.00 = 보통, 1.20 = 숙련, 0.80 = 신입).
-- 실데이터가 없어 센터 관리자가 정하는 값. 기본 1.00.
ALTER TABLE mfc_worker ADD COLUMN speed_factor numeric(3, 2) NOT NULL DEFAULT 1.00;
ALTER TABLE mfc_worker ADD CONSTRAINT ck_mfc_worker_speed CHECK (speed_factor BETWEEN 0.50 AND 2.00);
