-- 고객번호(2026-11-20 CU-3 — 사용자 "가린 이름 · 고객번호 6자리", ADR-0070 화면 번호 규칙): 숫자 6자리, 첫 자리 1~9.
-- 전체에서 겹치지 않는 무작위 번호(가입 순서·고객 수를 짐작할 수 없게), 한 번 정하면 바뀌지 않는다(탈퇴해도 이력 표시용으로 남김).
-- account_id 는 그대로 시스템 키. 새 고객은 INSERT 때 트리거가 채운다(겹치면 다시 뽑음). 기존 고객은 여기서 채운다. rider V10 과 같은 방식.
ALTER TABLE customer ADD COLUMN customer_number varchar(6);
CREATE UNIQUE INDEX ux_customer_number ON customer (customer_number);

CREATE OR REPLACE FUNCTION customer_assign_number() RETURNS trigger AS $$
DECLARE
  n varchar(6);
BEGIN
  IF NEW.customer_number IS NULL THEN
    LOOP
      n := (100000 + floor(random() * 900000))::int::text;
      EXIT WHEN NOT EXISTS (SELECT 1 FROM customer WHERE customer_number = n);
    END LOOP;
    NEW.customer_number := n;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_customer_number BEFORE INSERT ON customer
  FOR EACH ROW EXECUTE FUNCTION customer_assign_number();

DO $$
DECLARE
  r record;
  n varchar(6);
BEGIN
  FOR r IN SELECT account_id FROM customer WHERE customer_number IS NULL ORDER BY joined_at LOOP
    LOOP
      n := (100000 + floor(random() * 900000))::int::text;
      EXIT WHEN NOT EXISTS (SELECT 1 FROM customer WHERE customer_number = n);
    END LOOP;
    UPDATE customer SET customer_number = n WHERE account_id = r.account_id;
  END LOOP;
END $$;

-- 직원 찾기(전화 완전 일치) — 프로필 휴대폰 번호(숫자만)
CREATE INDEX ix_customer_phone ON customer (phone) WHERE phone IS NOT NULL;
