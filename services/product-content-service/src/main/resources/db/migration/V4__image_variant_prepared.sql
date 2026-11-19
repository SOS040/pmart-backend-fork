-- 축소 이미지 미리 만들기(2026-11-20, 상품 상세 느림 — 사용자 "응"): 이미지 내용(sha256)마다 240·480·720 축소본을 만들어 둔 기록.
-- 축소본 자체는 저장소 variants/w<폭>/<sha256>.jpg(내용 주소). 이 표에 없는 내용을 뒤에서 차례로 만든다(VariantPreparer).
CREATE TABLE content_image_variant (
    sha256      varchar(64)  PRIMARY KEY,
    prepared_at timestamptz  NOT NULL
);
