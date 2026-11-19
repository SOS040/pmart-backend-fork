# 요청 — 상품 상세가 느림: 설명 이미지를 원본 해상도로 푸는 문제 + 평점 집계 API 열림

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 근거: 사용자 2026-11-19 "상품 상세 누르면 느려" → 서버·앱 확인(아래) → 앱 쪽 원인으로 판단
- 받는 곳: customer-app(`apps/customer_app/lib/features/catalog`)

## 확인한 것(LAN, 2026-11-19)
- **서버는 빠르다.** 상세를 열 때 앱이 부르는 것은 둘뿐 — `GET /product-contents/{id}?mfcId=`(42~63ms)·`GET /products/{id}/reviews`(22~39ms). 가격은 상세 응답에 이미 있어(BR-36) `GET /prices` 추가 호출은 일어나지 않는다.
- **이미지가 크다.** 상품 하나에 설명 조각(DESCRIPTION) 5~7장 + 상세정보(DETAIL) 1장, 각 **1080×2000 px·150~450KB** → 받는 양 약 1.4MB, **화면에 원본 해상도로 풀면 약 45MB**(3개 상품 표본 21장 4.2MB·135MB). 서버가 이미지 한 장을 주는 데는 33~58ms.
- `widgets/content_image.dart` 의 `Image.network` 에 `cacheWidth` 가 없어 1080×2000 을 그대로 디코딩한다. 상세 목록은 `ListView(children: …)`.

## 요청
1. **`ContentImage`·대표 이미지에 `cacheWidth` 주기** — 화면 폭 × `MediaQuery.devicePixelRatioOf(context)`(정수로) 로 디코딩. 원본 비율은 그대로라 화면은 같고, 메모리·디코딩 시간이 크게 준다.
2. **상세는 글·옵션·가격을 먼저**, 설명 이미지는 스크롤해 들어올 때 — `ListView.builder`(또는 지금 구조에서 이미지가 화면 밖이면 만들지 않게).
3. 느림을 다시 재 볼 때는 **release(또는 profile) 빌드**로 — debug 빌드는 이미지 디코딩·첫 그리기가 몇 배 느리다.
4. 그래도 느리면 알려 달라 — 서버가 휴대폰용으로 줄인 이미지(예 `?w=720`)를 따로 주는 것을 만든다(지금은 원본만).

## 덧붙임 — 평점 집계 API(product-content 0.2.1)
- `GET /product-contents/{productId}/rating-summary` — **비회원도**(토큰 없이). `{productId, reviewCount, averageRating(리뷰 없으면 null — 0점 아님, 소수 첫째 자리), ratingCounts{"1"~"5"}, asOf}`.
- 리뷰 등록·수정·삭제 사건으로 채워서 등록 직후 몇 초 늦을 수 있다. 상세의 별점·리뷰 수·별점 막대에 쓰면 된다(리뷰를 다 받아 앱이 세지 말 것).
- 상태: 백엔드 main 반영, LAN 배포는 사용자 승인 뒤(배포되면 아래에 적는다).
