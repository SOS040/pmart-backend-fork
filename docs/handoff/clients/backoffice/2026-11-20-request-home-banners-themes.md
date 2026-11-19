# [pmart-backend → pmart-clients] 요청: 백오피스 "홈 진열 편성" 화면 — 일반 배너·상품 테마 (2026-11-20)

- 보낸 영역: platform(PC 1)
- 받는 앱: backoffice(`web/backoffice` 상품·전시 묶음) — 담당 PC 2
- 근거: ADR-0075 ②(BackOffice BO-05 일반 배너·상품 테마), 사용자 결정 2026-11-20 "프로모션부터 하자" → 범위 "탭 + 배너·테마"(할인·쿠폰 없음)
- 계약: storefront-service **0.7.0** — 태그 `layouts`(`/storefront/layout-drafts…`, `/storefront/banner-images`), 스키마 `LayoutDraftBody`·`LayoutDraft`·`LayoutDraftSubmission`·`BannerImage`
- 같이 보는 것: 콘텐츠 게시 화면(product-content 초안 → 제출 → approval) — **같은 모양**이다

## 무엇
MD 가 고객 앱 홈의 **배너**와 **상품 테마 영역**(예: "겨울 제철 채소")을 만들고, 전시 승인자(DISPLAY_APPROVER)가 승인하면 홈에 나온다.

- 누가 편성: `MD`·`OPERATOR_ADMIN`(그 밖 403 — 메뉴를 숨긴다)
- 누가 승인: `DISPLAY_APPROVER`(승인함, 기존 콘텐츠 게시 승인과 같은 종류 DISPLAY). 자기가 낸 것은 자기 승인 불가

## 흐름
1. **배너 이미지 올리기** `POST /api/v1/storefront/banner-images` (multipart `file`, Idempotency-Key, X-Source-System: backoffice)
   - JPEG·PNG·WEBP, 5MB 이하. 아니면 422 `IMAGE_INVALID`
   - 응답 `imageId`·`url`·`width`·`height` — 미리보기는 `url` 을 운영자 토큰으로 GET(게시 전 이미지는 고객에게 404)
2. **초안 만들기** `POST /api/v1/storefront/layout-drafts` (본문 `LayoutDraftBody`)
   - `?fromCurrent=true` 면 **지금 게시본을 복사**해 시작(본문 무시) — "지금 홈 고치기"는 이것으로
   - 본문
     - `banners[]`: `bannerId`(초안 안에서 고유, 예 B1), `placement` HOME_TOP·HOME_MIDDLE, `imageId`, `altText`, `linkType` SECTION·PRODUCT·CATEGORY·NONE, `linkRef`, `sortOrder`, `startsAt`·`endsAt`(비우면 무기한)
     - `themes[]`: `sectionId`(`T-` 로 시작, 예 T-WINTER), `kind` THEME·SEASON·WEEKLY_PICK, `title`, `subtitle`, `productIds`(1~50, **넣은 순서대로 보인다**), `sortOrder`, `startsAt`·`endsAt`
     - `note`(편성 메모, 고객에게 안 보임)
   - 링크: SECTION = 같은 초안의 테마 `sectionId`, PRODUCT = 상품 ID, CATEGORY = 카테고리 ID, NONE = `linkRef` 비움
3. **고치기** `PUT /api/v1/storefront/layout-drafts/{draftId}` (`X-Expected-Version` = 받은 `version`, 다르면 409 — 다시 조회)
   - DRAFT·REJECTED 만. 제출·게시된 초안은 고칠 수 없다(새 초안)
4. **제출** `POST /api/v1/storefront/layout-drafts/{draftId}/submissions`
   - 걸린 곳이 있으면 422 `LAYOUT_INVALID` — `details[]` 에 **모두** 온다(field·reason). 필드 옆에 표시해 주세요:

     | reason | 뜻 |
     |---|---|
     | `PROMOTION_NOT_ALLOWED` | 1차 범위 밖 문구(할인·"30% off"·쿠폰·특가·증정·사은품·1+1·세일·최저가·신선 보장). "100% 국내산" 같은 %는 통과 |
     | `PRODUCT_NOT_PUBLISHED` | 진열할 수 없는 상품(게시 안 됨·판매중지) — field = 상품 ID |
     | `IMAGE_NOT_FOUND` | 올리지 않은 이미지 |
     | `INVALID_LINK` | 링크 대상이 없음·NONE 인데 값이 있음 |
     | `INVALID_PERIOD` | 시작 ≥ 끝 |
     | `INVALID_PLACEMENT` | 자리 값 |
     | `DUPLICATE_ID` / `DUPLICATE_PRODUCT` | 배너·영역 ID 중복 / 한 테마 안 같은 상품 |

   - 통과하면 200 `LayoutDraftSubmission` — `approvalRequest` 를 **그대로** approval-service `POST /api/v1/change-requests` → `POST /change-requests/{id}/submissions` 로 보낸다(콘텐츠 게시와 같음)
5. **승인 뒤**: 초안 `state` 가 PUBLISHED, `layoutVersion` = 새 홈 버전. 반려면 REJECTED(`rejectionReason`) — 고쳐서 다시 제출
   - `SCOPE_MISMATCH` = 승인한 내용과 잠근 초안이 다름(반영 안 됨) — 새로 제출
6. **목록** `GET /api/v1/storefront/layout-drafts?state=` — `currentLayoutVersion`(지금 홈 버전)도 온다

## 화면에 있으면 좋은 것
- 테마 상품 고르기: 상품 검색(기존 상품 목록 API) → 순서 끌어 바꾸기
- 미리보기: 초안 본문으로 그리기(실제 홈은 승인 뒤에만 바뀐다)
- 철회: `fromCurrent=true` 로 복사 → 배너·테마 빼거나 `endsAt` 을 지금으로 → 제출. **빈 본문도 제출할 수 있다**

## 하지 않는 것(1차)
할인율·쿠폰·특가·증정, 고객 대상 나누기(세그먼트), 센터별 배너. 판매중지된 상품은 승인 뒤에도 고객 홈에서 그 자리에서만 빠진다(영역은 남음).

## 배포 상태
- 백엔드 구현·시험 끝(storefront `LayoutIT`). LAN 배포는 사용자 승인 뒤 — 배포되면 이 문서에 날짜를 적는다.
