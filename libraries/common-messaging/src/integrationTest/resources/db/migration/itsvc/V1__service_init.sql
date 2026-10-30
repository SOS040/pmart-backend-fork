-- 시험용 서비스 마이그레이션(FlywayCommonLocationPostgresIT): 공통 V0_x 보다 먼저 적용된 V1 을 흉내 낸다.
CREATE TABLE it_service_marker (id integer PRIMARY KEY);
