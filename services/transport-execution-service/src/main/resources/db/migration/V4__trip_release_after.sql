-- 2026-11-27 DC 시뮬레이터 R-29(사용자 "mfc 에서 하차완료해야 자동으로 운행종료지"): 인계 → 정차 자동 출발, 마지막 정차 출발 → 운행 자동 COMPLETED.
-- 차량은 아직 DC 로 돌아오는 중이라 fleet 점유는 복귀 시간(return_minutes) 뒤에 푼다(사용자 결정 "자동 종료 + 복귀 시간 더함").
-- NULL = 바로 풀어도 됨(예전 DC 복귀 등록). DC 복귀를 등록하면 그 시각으로 당긴다.
ALTER TABLE trip ADD COLUMN release_after timestamptz;
