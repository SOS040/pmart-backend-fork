-- 리뷰 요청 알림(2026-11-18, 사용자 결정 "A해줘", 계약 0.4.2 — 설명만).
-- 고객 주문 배송 완료(DeliveryCompleted)마다 주문 하나에 한 줄(주문 키 — 같은 주문의 다른 배송·재전달은 새로 만들지 않는다).
-- 정리 작업이 완료 시각 + pmart.notification.review-request.delay 가 지난 줄을 골라 review-service 에 리뷰를 썼는지 묻고,
-- 안 썼으면 고객 알림함에 REVIEW_REQUESTED 를 한 번 둔다. 앱 알림함만(휴대폰 푸시 종류 목록에 넣지 않는다).
-- review-service 가 응답하지 않으면 PENDING 그대로 다음에 다시, 완료 시각 + max-age 가 지나면 EXPIRED.
CREATE TABLE review_request (
    order_id         varchar(64)  NOT NULL,
    account_id       varchar(64)  NOT NULL,
    completed_at     timestamptz  NOT NULL, -- 배송 완료 시각(사건의 deliveredAt — 없으면 사건 시각)
    source_event_id  varchar(64)  NOT NULL, -- 처음 기록한 DeliveryCompleted 사건 ID
    state            varchar(24)  NOT NULL, -- PENDING / SENT / SKIPPED_REVIEWED / SKIPPED_NOT_ELIGIBLE / EXPIRED
    attempts         integer      NOT NULL DEFAULT 0, -- review-service 조회 실패 횟수
    last_error       varchar(40),           -- 마지막 실패 코드(DEPENDENCY_UNAVAILABLE 등 — 개인정보 없음)
    last_attempt_at  timestamptz,
    notification_id  varchar(64),           -- SENT 면 알림 ID
    created_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    CONSTRAINT pk_review_request PRIMARY KEY (order_id)
);
CREATE INDEX ix_review_request_pending ON review_request (completed_at) WHERE state = 'PENDING';
