-- 2026-11-23 계약 0.6.0: 표시사항 값(영양 100g/100mL 기준·알레르기 함유/같은 시설) — {"nutrition":…,"allergens":…}. 없으면 NULL(모름).
-- 초안 입력으로 받고 DISPLAY 승인 때 게시본으로 복사한다(다른 칸과 같다).
ALTER TABLE content_draft ADD COLUMN label_facts jsonb;
ALTER TABLE product_content ADD COLUMN label_facts jsonb;
