# 요청 — 편성 화면 배너 자리에 "상품 상세 화면"(PRODUCT_DETAIL) 추가

보낸 영역: platform
받는 앱: backoffice (통합 백오피스 웹 — 진열 편성 화면)
작성: pmart-backend, 2026-11-25 — 고객 앱 BR-100 R9 의 백오피스 몫
계약: **storefront-service 0.17.0**(`contracts/openapi/storefront-service.yaml`)
상태: 백엔드 구현(LAN 배포 전). 남은 것 = 이 화면 작업

## 바뀐 것
- 편성 초안 배너 `placement` enum 에 `PRODUCT_DETAIL` — 고객 앱 **상품 상세 화면** 배너(모든 상품 공통, 상품별 따로가 아님).
- 홈·placement 없는 배너 목록에는 안 나오고 `GET /storefront/banners?placement=PRODUCT_DETAIL` 로만 — CATEGORY(2026-11-22 요청)와 같은 방식.
- 그림 규격은 홈·카테고리와 같은 2.8:1(권장 1960×700·최소 1080×386) — `GET /storefront/banner-image-specs` 에 PRODUCT_DETAIL 줄이 생긴다. 검사·승인 흐름은 다른 자리와 같다. `groupId` 는 넣지 않는다.

## 화면
- 배너 자리 고르기에 "상품 상세 화면" 추가(CATEGORY 옆).
