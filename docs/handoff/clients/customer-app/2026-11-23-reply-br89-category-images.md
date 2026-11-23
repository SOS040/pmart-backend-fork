# 회신 — BR-89 카테고리 대표 사진

보낸 영역: platform
받는 앱: customer-app
요청: pmart-clients `docs/handoff/customer-app/2026-11-23-backend-category-image.md`(BR-89)
작성: pmart-backend, 2026-11-23 — 사용자 "응"(카테고리마다 인기 1위 상품 사진 자동)
상태: LAN 배포 2026-11-23(search 0.13.0 · 게이트웨이 공개 경로 — pmart-config 8562af9)

## 요청과 다른 점
- `CategoryNode.imageUrl`(product-service) 이 아니라 **search-service 별도 조회**로 준다. product-service 에는 상품 사진·판매 정보가 없고, 거기서 검색을 부르면 서비스끼리 서로 부르는 꼴이 된다(검색이 이미 product-service 트리를 읽는다).

## API — search 0.13.0 `GET /search/category-images?mfcId=` (로그인 없이)
- 응답 `items[{categoryId, imageUrl, productId}]` — 상위·하위·칩 모두, **상품이 있는 카테고리만**(없으면 목록에 없음 → 앱 그림).
- 사진 = 그 카테고리(하위 포함)의 판매 가능 상품 중 최근 판매 많은 → 별점 → 최근 게시 순 1위의 대표 사진. mfcId 를 주면 그 센터가 취급하는 상품만. 서버 5분 사본.
- 카테고리 트리를 받은 뒤 한 번 불러 categoryId 로 맞추면 된다.

## Q1 크기·형식
- `imageUrl` 은 상품 사진 경로(`/api/v1/content-images/{id}`). 동그라미에는 `?w=240` 을 붙여 쓴다(JPEG, 원본 비율 — 대부분 상품 사진이라 정사각에 가깝고 흰 바탕). 앱 회색 동그라미 안에 contain 권장.
