-- 피킹 방문 순서(2026-11-09, 계약 0.4.10, ADR-0050 B2): PICK 시작 때 mfc-capacity 가 정한 칸 순서. 상세가 이 순서로 줄·조각을 보인다.
-- 없으면(capacity 장애 등) 상세가 칸 코드 순으로 대신한다.
CREATE TABLE task_pick_route (
    task_id       varchar(80)  NOT NULL,
    location_code varchar(32)  NOT NULL,
    seq           integer      NOT NULL,
    method        varchar(20)  NOT NULL,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT pk_task_pick_route PRIMARY KEY (task_id, location_code)
);
