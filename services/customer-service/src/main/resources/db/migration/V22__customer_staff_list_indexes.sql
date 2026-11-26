-- 직원 고객 목록(2026-11-26 CU-5): 가입 최근 순 쪽 넘김, 이름 앞부분 일치(text_pattern_ops — LIKE 'abc%').
CREATE INDEX ix_customer_joined ON customer (joined_at DESC, account_id);
CREATE INDEX ix_customer_name_prefix ON customer (name text_pattern_ops) WHERE name IS NOT NULL;
