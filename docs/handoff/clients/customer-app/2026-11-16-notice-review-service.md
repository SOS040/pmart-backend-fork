# [pmart-backend → pmart-clients] 알림: 리뷰 서버 구현 — 계약 0.2.0(사진 올리기 추가) (2026-11-16)

- 보낸 영역: platform
- 받는 앱: customer_app (`lib/features/reviews` — 지금 목 데이터)
- 계약: `contracts/openapi/review-service.yaml` **0.2.0**. 결정 ADR-0063. LAN 배포 상태는 아래 §4.

## 1. 0.1.0 그대로 쓰는 것
- `GET /me/review-eligibilities?orderId=&state=&size=&cursor=` · `POST /reviews` · `GET/PATCH/DELETE /reviews/{reviewId}` · `GET /products/{productId}/reviews?sort=&withPhotoOnly=` — 필드·오류 코드는 앱의 `reviews.dart` 와 같다.
- 자격은 **배송 완료(라이더가 전달 완료 사진을 올린 뒤)** 에 생긴다. 주문 상태만 보고 "리뷰 쓰기" 를 띄우지 말고 자격 목록을 본다.
- `Eligibility` 에 **`productName`**(주문 당시 상품명, 없을 수 있음)이 더해졌다 — 작성 화면 제목에 쓸 수 있다.
- 작성자 표시 `authorMasked` 는 별명 첫 글자 + `*`(예 `장***`), 별명이 없으면 `PMart 고객`.
- 상태: 작성 가능 `REV_ELIGIBLE`, 기한 지남 `EXPIRED`, 쓴 뒤 3일 안 `PUBLISHED`, 3일 지나면 `LOCKED`(공개 유지·수정 불가), `DELETED`.
- 목록 `summary.averageRating` 은 소수 첫째 자리, 리뷰가 없으면 null·`reviewCount` 0.
- `NOT_ELIGIBLE` 에는 **받은 수량이 모두 반품된 품목**도 들어간다(사용자 결정 — 이미 쓴 리뷰는 그대로). 반품 기능이 생기기 전에는 생기지 않는다.

## 2. 새로 붙일 것 — 사진
1. `POST /review-photos` (multipart `file`, 헤더 `Idempotency-Key`·`X-Source-System`) → 201 `{photoId, url, contentType, sizeBytes, uploadedAt}`. 한 장씩, **장당 10MB**, JPEG·PNG·WEBP(파일 내용으로 판정 — 아니면 422 `PHOTO_INVALID`).
2. 작성·수정의 `photoIds` 에 넣는다(**리뷰당 5장**). 내가 올린 사진이 아니거나 다른 리뷰에 쓴 사진이면 422 `PHOTO_NOT_FOUND`.
3. 수정에서 `photoIds` 를 주면 **그 목록으로 바뀐다**(빠진 사진은 서버가 정리). 사진을 그대로 두려면 `photoIds` 를 빼고 보낸다.
4. 사진 보기 `GET /review-photos/{photoId}` 는 토큰 없이 `<img>`/`Image.network` 로 — **공개 리뷰에 붙은 뒤에만** 열린다(올리기만 한 사진은 404). 미리보기는 기기에 있는 원본으로.
5. 24시간 안에 리뷰에 붙지 않은 사진은 지운다 — 작성 화면을 오래 열어 두었다면 다시 올리게.

## 3. 주의
- 사진 업로드 실패와 리뷰 등록 실패를 따로 보인다(업무 원문 15.6). 업로드가 일부 실패하면 성공한 photoId 만 보내지 말고 사용자에게 묻는다.
- 운영자 숨김·신고 버튼은 아직 없다(사용자 결정) — 화면에 두지 않는다.
- 상품 카드·상세의 별점 집계(product-content `rating`)는 아직 리뷰를 반영하지 않는다. 상세의 리뷰 영역은 `GET /products/{id}/reviews` 의 `summary` 를 쓴다.

## 4. LAN
- **2026-11-16 LAN 배포됨**(PC 2 review-service 19e94fae, 게이트웨이 PC 4). `https://192.168.0.40:8443/api/v1/...` — 상품 리뷰 목록 200, 없는 리뷰·사진 404, 자격 목록 토큰 없이 401 확인.
- LAN 에는 아직 **전달 완료된 배송이 없어** 자격 목록이 비어 있다. 실제 화면 확인은 라이더 앱으로 배송을 완료한 뒤부터.
