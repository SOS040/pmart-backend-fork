-- 첫 구매 무료배달 중복 대조값(2026-10-31). 계정이 첫 구매 혜택을 쓴 기록 — 주문 확정 사건(이후 단계)이 채운다.
-- 탈퇴 후 재가입 대조(소셜 식별 5년 보존)는 이 표를 소셜 식별 기준으로 넓혀 붙인다(그 전에는 계정 기준만).
CREATE TABLE first_purchase_used (
    account_id varchar(64)  NOT NULL,
    order_id   varchar(64)  NOT NULL,
    used_at    timestamptz  NOT NULL,
    CONSTRAINT pk_first_purchase_used PRIMARY KEY (account_id)
);
