-- 리뷰 별점 사본(2026-11-19, 계약 0.2.1 — 상품 상세 평점 집계). review-service 가 정본, 여기는 commerce.review.v1 사건 사본.
-- 리뷰마다 한 줄 — 수정(ReviewPublished 다시)·삭제(ReviewDeleted)는 리뷰 버전이 큰 것만 반영한다(늦게 온 옛 사건 무시).
CREATE TABLE review_rating (
    review_id   varchar(64)  NOT NULL,
    product_id  varchar(40)  NOT NULL,
    rating      smallint     NOT NULL,
    deleted     boolean      NOT NULL,
    version     integer      NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT pk_review_rating PRIMARY KEY (review_id),
    CONSTRAINT ck_review_rating_rating CHECK (rating BETWEEN 1 AND 5)
);
CREATE INDEX ix_review_rating_product ON review_rating (product_id) WHERE NOT deleted;
