-- 라이더에게 보일 주문 요약 사본(2026-11-16 — 라이더 앱 요청 §1, 사용자 "수락 전부터 전부"): order rider-summary + center-master 지점 이름·주소.
-- 좌표를 준비할 때 한 번 받아 둔다(제안 조회는 5초마다라 order 를 매번 부르지 않는다). 고객 이름·연락처·주소는 없다. 배송이 끝나면 다른 행처럼 남는다.
ALTER TABLE delivery ADD COLUMN order_summary jsonb;
