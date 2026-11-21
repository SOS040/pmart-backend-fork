-- 도움돼요(2026-11-21 BR-76). 회원·리뷰마다 한 줄, 수는 review.helpful_count 에 같이 센다(목록 정렬 RECOMMENDED).
ALTER TABLE review ADD COLUMN helpful_count integer NOT NULL DEFAULT 0;
ALTER TABLE review ADD CONSTRAINT ck_review_helpful_count CHECK (helpful_count >= 0);
CREATE TABLE review_helpful (
    review_id    varchar(64)  NOT NULL,
    account_id   varchar(64)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT pk_review_helpful PRIMARY KEY (review_id, account_id)
);
CREATE INDEX ix_review_recommended ON review (product_id, helpful_count DESC, created_at DESC) WHERE state = 'PUBLISHED';
