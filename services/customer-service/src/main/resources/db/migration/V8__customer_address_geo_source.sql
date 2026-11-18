-- 배송지 좌표 출처(2026-11-18, 클라이언트 요청 rider-app drop-exact-coordinates 2 — 사용자 "응 해줘").
-- PIN = 고객이 지도 핀으로 고름, GEOCODE = 핀이 없어 서버가 도로명주소를 지오코딩(delivery-serviceability)해 채움.
-- 예전 행: 좌표가 있으면 PIN(핀으로만 들어왔음), 없으면 NULL.
ALTER TABLE customer_address ADD COLUMN geo_source varchar(16);
UPDATE customer_address SET geo_source = 'PIN' WHERE lat IS NOT NULL AND lng IS NOT NULL;
