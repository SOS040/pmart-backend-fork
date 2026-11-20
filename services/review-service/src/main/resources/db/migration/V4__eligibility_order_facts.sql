-- 리뷰관리 주문 묶음 머리(2026-11-20, 계약 0.5.0 — BR-67, 사용자 "추천대로"): 자격을 만들 때 읽은 order 내부 주문의 확정 시각·담당 센터 사본.
-- 주문 시각·센터는 바뀌지 않는 사실이라 한 번 저장한다. 이 열이 생기기 전 행은 비어 있고, 목록 조회 때 order 를 다시 읽어 채운다(실패면 비워 둔다).
-- 센터 이름은 저장하지 않는다 — 조회 때 center-master 에서 읽는다(이름이 바뀔 수 있다).
ALTER TABLE review_eligibility ADD COLUMN ordered_at timestamptz;
ALTER TABLE review_eligibility ADD COLUMN mfc_id varchar(40);
