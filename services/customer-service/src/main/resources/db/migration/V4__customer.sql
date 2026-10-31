-- 고객(회원) 기준 테이블(2026-10-31). 고객 한 명 = 한 행(account_id = auth 계정 ID).
-- 기존 customer_profile 을 이름만 바꿔 쓴다(데이터 유지). 주소록·쇼핑 주소·첫 구매 기록은 account_id 로 이 행에 붙는다.
-- 이름·이메일은 Commerce 4.x "내 정보"(닉네임·이름·이메일), 휴대폰 번호는 계약 /me/profile-changes 의 연락처.
-- 세 값은 아직 채우는 경로가 없다(로그인 사건에 없음, 프로필 변경은 501) — 비어 있을 수 있다. 개인정보라 로그 금지.
ALTER TABLE customer_profile RENAME TO customer;
ALTER TABLE customer RENAME CONSTRAINT pk_customer_profile TO pk_customer;

ALTER TABLE customer
    ADD COLUMN name  varchar(50),
    ADD COLUMN email varchar(254),
    ADD COLUMN phone varchar(20);
