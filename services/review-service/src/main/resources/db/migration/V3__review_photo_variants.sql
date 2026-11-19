-- 리뷰 사진 축소본(2026-11-20, 계약 0.4.0 — 리뷰 사진 느림): 240·480·720 축소본을 다 만든 시각. 비어 있으면 ReviewPhotoPreparer 가 뒤에서 만든다.
-- 축소본 본문은 사진 버킷 review-photo-variants/w<폭>/<photoId>.jpg
ALTER TABLE review_photo ADD COLUMN variants_at timestamptz;
