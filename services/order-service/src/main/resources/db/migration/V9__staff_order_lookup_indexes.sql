-- 직원용 주문 검색(2026-11-15, O-6 — 백오피스 고객센터·라이더 운영, 계약 0.3.0). 읽기 전용 조회의 색인만 더한다(표·열 변경 없음).
-- 고객 ID 검색은 기존 ix_customer_order_account(account_id, confirmed_at DESC, order_id DESC)를 쓴다.

-- 기간(확정 시각) 검색 + 최근 확정 순 정렬 — orderId·customerId 없는 검색은 기간 31일 이하가 필수라 이 색인 범위만 읽는다
CREATE INDEX ix_customer_order_confirmed ON customer_order (confirmed_at DESC, order_id DESC);

-- 센터별 기간 검색
CREATE INDEX ix_customer_order_mfc ON customer_order (mfc_id, confirmed_at DESC, order_id DESC);

-- 수령인 연락처 완전 일치(숫자만 비교, 하이픈 무시). 부분 일치 검색은 하지 않는다 — 개인정보
CREATE INDEX ix_customer_order_shipping_phone
    ON customer_order_shipping ((regexp_replace(receiver_phone, '[^0-9]', '', 'g')));
