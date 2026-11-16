-- 화면용 주문번호(2026-11-16 — 클라이언트 all 요청 "화면용 주문번호 8자리를 모든 앱이 같이"): 숫자 8자리, 첫 자리 1~9.
-- 전체에서 겹치지 않는 무작위 번호(일련번호가 아니라 주문량·다음 번호를 짐작할 수 없게). orderId 는 그대로 시스템 키.
-- 새 주문은 확정 때 OrderStore.insert 가 만든다(ON CONFLICT 로 겹치면 다시 뽑음). 기존 주문은 여기서 채운다.
ALTER TABLE customer_order ADD COLUMN order_number varchar(8);
CREATE UNIQUE INDEX ux_customer_order_number ON customer_order (order_number);

DO $$
DECLARE
  r record;
  n varchar(8);
BEGIN
  FOR r IN SELECT order_id FROM customer_order WHERE order_number IS NULL LOOP
    LOOP
      n := (10000000 + floor(random() * 90000000))::bigint::text;
      EXIT WHEN NOT EXISTS (SELECT 1 FROM customer_order WHERE order_number = n);
    END LOOP;
    UPDATE customer_order SET order_number = n WHERE order_id = r.order_id;
  END LOOP;
END $$;
