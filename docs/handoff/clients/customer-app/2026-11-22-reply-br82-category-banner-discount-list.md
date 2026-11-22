# 회신 — BR-82 카테고리 화면 배너·할인 상품 목록

보낸 영역: platform
받는 앱: customer-app
답하는 것: `pmart-clients/docs/handoff/customer-app/2026-11-22-backend-category-banner-discount-list.md`
작성: pmart-backend, 2026-11-22 — 사용자 "응 그것부터진행해줘"
계약: **storefront-service 0.11.0**, **search-service 0.8.0**
상태: **LAN 반영 2026-11-22 13:2x** — 이미지 62087c9e(PC 2 storefront·search)

## R1 카테고리 화면 배너 — 됨
- 편성 배너 자리에 `CATEGORY` 추가. `GET /storefront/banners?placement=CATEGORY` 로만 나오고 홈 `banners`·placement 없는 목록에는 안 섞인다(POPUP·SPLASH 와 같음). 모든 카테고리 화면 공통 한 자리(카테고리별 따로 아님).
- LAN 시험 배너 2장(진열 버전 7): `B-CAT-VEG` "친환경 채소 모아보기" → CATEGORY `p17-c01`, `B-CAT-MEAT` "정육·계란" → CATEGORY `p08-c01`(이미지는 홈 배너 것을 다시 씀). 홈 배너·팝업·테마·MD 탭은 그대로.
- 백오피스 편성 화면에서 이 자리를 고를 수 있게 하는 일은 통합 백오피스에 넘겼다(`backoffice/2026-11-22-request-category-banner-placement.md`).

## R2 할인 상품 목록 — `GET /search/products?onSale=true`
- 대표 SKU 에 **지금 기간 할인**이 있는 상품만(결과 `salePrice` 가 붙는 것과 같은 판정 — 할인 금액 > 0, 끝 시각 전, 가격 버전 같음). 검색어·카테고리·필터·가격 범위·정렬(SortKey)과 함께 쓴다.
- 할인율순 정렬은 아직 없다(필요하면 다시 요청). 할인 정보를 못 읽으면 503(전체를 보여 주지 않는다).
- LAN 지금 7개(아이스크림 20% 할인 — 모두 salePrice 있음).
- 카테고리 [할인] 타일을 쿠폰함 대신 `onSale=true` 목록으로 바꾸면 된다.
