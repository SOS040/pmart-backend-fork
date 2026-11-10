-- Keycloak 직원 차단 중계(ADR-0054 ⑤ 결정 M): 이미 처리한 Keycloak 관리 기록 키. 같은 기록으로 SubjectBlocked 를 두 번 내지 않게
-- (인스턴스가 여럿이어도, 다시 읽어도). 기록 시각이 되돌아 읽는 기간의 두 배보다 오래되면 지운다(StaffBlockRelay.prune).
CREATE TABLE staff_admin_event_seen (
    event_key   varchar(200) NOT NULL, -- Keycloak 관리 기록 id(없으면 시각|작업|경로)
    event_time  timestamptz  NOT NULL, -- Keycloak 기록 시각
    seen_at     timestamptz  NOT NULL,
    CONSTRAINT pk_staff_admin_event_seen PRIMARY KEY (event_key)
);
CREATE INDEX ix_staff_admin_event_seen_time ON staff_admin_event_seen (event_time);
