-- 로컬 전용: 서비스별 데이터베이스와 복제 권한. (운영은 서비스별 DB·계정 분리 + Secret Manager)
-- 사용자 pmart 는 superuser(로컬)이므로 Debezium 복제 슬롯 생성이 가능하다.
-- 서비스 DB 는 이름 규칙 {service-name 의 하이픈을 밑줄로}: order_service, mfc_inventory_service ...
-- 3단계 스캐폴딩에서 서비스가 늘어나면 여기에 한 줄씩 추가한다.
CREATE DATABASE order_service;
CREATE DATABASE mfc_inventory_service;
