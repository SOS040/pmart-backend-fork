-- 센터 SKU 취급 사본(2026-11-19, 클라이언트 BR-49 — mfcId 를 주면 미취급 상품을 진열에서 뺀다, 검색과 같은 기준).
-- 원천 mfc-inventory SkuHandlingChanged(토픽 mfc.sku-handling.v1, 무제한 보관 — 새 소비 그룹이 처음부터 다시 읽어 채운다).
-- 레코드 없음 = 취급 안 함(원천 규칙). 같은 (센터, SKU) 는 version 이 큰 것만 반영. 적용된 파일은 수정하지 않는다.
CREATE TABLE sku_handling (
    mfc_id            varchar(32)  NOT NULL,
    sku               varchar(32)  NOT NULL,
    handling_enabled  boolean      NOT NULL,
    version           integer      NOT NULL,
    changed_at        timestamptz  NOT NULL,
    CONSTRAINT pk_sku_handling PRIMARY KEY (mfc_id, sku)
);
