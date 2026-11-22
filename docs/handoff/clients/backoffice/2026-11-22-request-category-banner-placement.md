# 요청 — 편성 화면 배너 자리에 "카테고리 화면"(CATEGORY) 추가

보낸 영역: platform
받는 앱: backoffice (통합 백오피스 웹 — 진열 편성 화면)
작성: pmart-backend, 2026-11-22 — 사용자 "통합백오피스 프론트 세션으로 넘겨줘"(고객 앱 BR-82 R1 의 백오피스 몫)
계약: **storefront-service 0.11.0**(`contracts/openapi/storefront-service.yaml`)
상태: (배포 줄 아래)

## 바뀐 것
- 편성 초안 배너 `placement` enum 에 `CATEGORY` 추가 — 고객 앱 **카테고리 화면 맨 위** 배너(모든 카테고리 화면 공통, 카테고리별 따로가 아님).
- 홈 `banners`·`GET /storefront/banners`(placement 없음)에는 나오지 않고 `GET /storefront/banners?placement=CATEGORY` 로만 — POPUP·SPLASH 와 같은 방식.
- 검사·승인 흐름은 다른 자리와 같다(이미지·링크 SECTION·CATEGORY·PRODUCT·NONE·기간·sortOrder, DISPLAY 승인). `groupId` 는 HOME_INLINE 만 쓰므로 CATEGORY 에는 넣지 않는다.

## 부탁
1. 편성 화면 배너 자리 고르기에 **"카테고리 화면"(CATEGORY)** 을 넣는다(지금 HOME_TOP·HOME_MIDDLE·POPUP·SPLASH·HOME_INLINE).
2. 미리보기가 있으면 CATEGORY 배너는 홈 미리보기에 넣지 않는다(카테고리 화면 자리로 따로 보이거나 목록만).
3. 고객 앱은 이미 `placement=CATEGORY` 로 읽는다(비면 자리를 비움). LAN 시험 배너는 백엔드가 1~2장 편성해 둔다 — 화면에서 고치거나 새로 만들어 확인하면 된다.

## 참고
- 동의어 관리 화면 요청(`backoffice/2026-11-20-request-search-synonym-screen.md`)은 그대로 열려 있다. LAN 에 처음 동의어 28개가 규칙 버전 1 로 들어갔다(2026-11-22) — 화면의 기준 버전은 `GET /search/synonym-rules` 의 version.
