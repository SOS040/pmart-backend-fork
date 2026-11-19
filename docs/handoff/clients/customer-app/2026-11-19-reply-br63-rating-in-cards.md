# 회신 — BR-63 홈 진열·상품 상세의 평점·리뷰 수

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: `pmart-clients docs/handoff/customer-app/2026-11-19-backend-rating-null-in-cards.md`
- 상태: 백엔드 main 반영, LAN 배포는 사용자 승인 뒤(배포되면 아래에 적는다)

## 원인
- 리뷰 시드 문제가 아니었다 — 리뷰 사건은 정상으로 흘러 평점 집계(rating-summary)는 480개였다.
- 상세·카드 응답의 `rating` 칸을 채우는 코드가 없었고, 홈 진열(storefront)은 리뷰를 전혀 받지 않았다.

## 바뀐 것
- R1 상세·카드(product-content 0.3.0): `GET /product-contents/{id}`·`GET /product-contents?productIds=` 의 `rating` = 평점 집계와 같은 모양
  `{productId, reviewCount, averageRating, ratingCounts, asOf}`. 리뷰가 없으면 `reviewCount 0`·`averageRating null`.
- R1 홈 진열(storefront 0.4.0): 항목 `rating`(평균, 소수 첫째 — 리뷰 없으면 **null**, 0점 아님)·`reviewCount`(없으면 0). storefront 가 리뷰 사건을 직접 받아 센다.
- R2 기존 리뷰: 다시 채울 것 없음 — product-content 는 이미 사본이 있고, storefront 는 배포 때 리뷰 토픽을 처음부터 읽어 채운다.
- 앱: 상세는 지금처럼 rating-summary 를 써도 되고 상세 응답 `rating` 을 써도 같다(호출 하나를 줄일 수 있다). 카드는 그대로 보인다.
