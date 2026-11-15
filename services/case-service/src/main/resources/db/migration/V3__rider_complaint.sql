-- 라이더 컴플레인 기록(2026-11-15, 계약 0.3.0, service-requests case R-5 — RS-3): 상담원이 백오피스에서 신고·오배송·파손을 기록한다.
-- 한 행 = 한 건. 같은 트랜잭션에서 RiderComplaintRecorded(platform.case.v1)를 Outbox 에 쓴다 — rider-service 가 센다.
-- 다른 서비스 테이블 참조 없음 — rider_id·delivery_id·order_id 는 ID 사본(rider_id 는 dispatch 배정 조회로 채움).
-- note 는 개인정보가 들어갈 수 있다 — 로그·사건에 싣지 않는다.
CREATE TABLE rider_complaint (
    complaint_id    varchar(64)   NOT NULL,
    case_id         varchar(64),
    rider_id        varchar(64)   NOT NULL,
    delivery_id     varchar(64)   NOT NULL,
    order_id        varchar(64),
    inquiry_id      varchar(64),
    complaint_type  varchar(20)   NOT NULL,
    source          varchar(30)   NOT NULL,
    note            text,
    recorded_by     varchar(64)   NOT NULL,
    recorded_at     timestamptz   NOT NULL,
    CONSTRAINT pk_rider_complaint PRIMARY KEY (complaint_id),
    CONSTRAINT ck_rider_complaint_type CHECK (complaint_type IN ('REPORT', 'MISDELIVERY', 'DAMAGE')),
    CONSTRAINT fk_rider_complaint_case FOREIGN KEY (case_id) REFERENCES case_record (case_id),
    CONSTRAINT fk_rider_complaint_inquiry FOREIGN KEY (inquiry_id) REFERENCES cs_inquiry (inquiry_id)
);
CREATE INDEX ix_rider_complaint_recorded ON rider_complaint (recorded_at DESC, complaint_id DESC);
CREATE INDEX ix_rider_complaint_rider ON rider_complaint (rider_id, recorded_at DESC, complaint_id DESC);
CREATE INDEX ix_rider_complaint_delivery ON rider_complaint (delivery_id);
