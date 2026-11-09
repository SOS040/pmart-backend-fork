-- 도면 위 위치(2026-11-09, 계약 0.6.0, ADR-0050): 작업자가 서서 집는 지점(미터, 센터 도면 기준). 피킹 동선 거리표 입력. 도면은 MFC 마다 다르다.
ALTER TABLE location ADD COLUMN pos_x_m numeric(7, 2), ADD COLUMN pos_z_m numeric(7, 2);
