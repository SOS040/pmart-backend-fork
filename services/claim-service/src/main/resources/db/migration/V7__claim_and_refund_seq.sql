-- 화면 번호(2026-11-20 CL-4, ADR-0070 덧붙임 2026-11-19 추천안 — 사용자 "진행해라"): 클레임은 "<주문번호> <종류> <순번>"(예 74939255 반품 1),
-- 환불은 "<주문번호> 환불 <순번>". claim_seq = 같은 주문·같은 종류(CANCEL/RETURN/EXCHANGE) 안에서 1부터, refund_seq = 같은 주문의 환불(상품·회수비 모두) 1부터.
-- 새 행은 INSERT 트리거가 주문마다 advisory lock 을 잡고 다음 번호를 준다(dispatch V13 delivery_seq 와 같은 방식). 기존 행은 생성순으로 채운다.
ALTER TABLE claim ADD COLUMN claim_seq integer;

WITH s AS (
  SELECT claim_id, row_number() OVER (PARTITION BY order_id, type ORDER BY opened_at, claim_id) AS n
    FROM claim
)
UPDATE claim c SET claim_seq = s.n FROM s WHERE c.claim_id = s.claim_id;

ALTER TABLE claim ALTER COLUMN claim_seq SET NOT NULL;
CREATE UNIQUE INDEX ux_claim_order_type_seq ON claim (order_id, type, claim_seq);

CREATE OR REPLACE FUNCTION claim_assign_seq() RETURNS trigger AS $$
BEGIN
  IF NEW.claim_seq IS NULL THEN
    PERFORM pg_advisory_xact_lock(hashtext('claim-seq:' || NEW.order_id));
    SELECT COALESCE(max(claim_seq), 0) + 1 INTO NEW.claim_seq
      FROM claim WHERE order_id = NEW.order_id AND type = NEW.type;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_claim_seq BEFORE INSERT ON claim
  FOR EACH ROW EXECUTE FUNCTION claim_assign_seq();

ALTER TABLE refund_intent ADD COLUMN refund_seq integer;

WITH s AS (
  SELECT refund_intent_id, row_number() OVER (PARTITION BY order_id ORDER BY created_at, refund_intent_id) AS n
    FROM refund_intent
)
UPDATE refund_intent r SET refund_seq = s.n FROM s WHERE r.refund_intent_id = s.refund_intent_id;

ALTER TABLE refund_intent ALTER COLUMN refund_seq SET NOT NULL;
CREATE UNIQUE INDEX ux_refund_intent_order_seq ON refund_intent (order_id, refund_seq);

CREATE OR REPLACE FUNCTION refund_intent_assign_seq() RETURNS trigger AS $$
BEGIN
  IF NEW.refund_seq IS NULL THEN
    PERFORM pg_advisory_xact_lock(hashtext('refund-seq:' || NEW.order_id));
    SELECT COALESCE(max(refund_seq), 0) + 1 INTO NEW.refund_seq
      FROM refund_intent WHERE order_id = NEW.order_id;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refund_intent_seq BEFORE INSERT ON refund_intent
  FOR EACH ROW EXECUTE FUNCTION refund_intent_assign_seq();
