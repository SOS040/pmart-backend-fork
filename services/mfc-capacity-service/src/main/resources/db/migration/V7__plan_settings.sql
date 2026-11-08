-- 배정 추천에 쓴 설정과 한계(2026-11-08 검토 반영 #33·#34·#36): 계산 때의 작업 시간·가중치·패킹대·시간 제한과
-- 근무 미등록(가용성 미확인) 작업자, 모델 한계를 계획마다 남긴다 — 결과를 운영 정책으로 오해하지 않게, 어떤 값으로 계산했는지 추적.
ALTER TABLE assignment_plan ADD COLUMN settings text;
ALTER TABLE assignment_plan ADD COLUMN unverified_workers text;
