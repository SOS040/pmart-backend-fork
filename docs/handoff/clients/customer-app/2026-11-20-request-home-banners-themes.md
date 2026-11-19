# [pmart-backend → pmart-clients] 요청: 고객 앱 홈 — 배너·테마 영역 표시 (2026-11-20)

- 보낸 영역: platform(PC 1)
- 받는 앱: customer-app
- 근거: ADR-0075 ②(BO-05 일반 배너·상품 테마), 사용자 결정 2026-11-20 "탭 + 배너·테마"
- 계약: storefront-service **0.7.0**(홈 응답 모양은 0.6.0 과 같다 — 값이 채워지기 시작할 뿐)

## 무엇이 바뀌나
지금까지 `GET /api/v1/storefront/home` 은 `banners: []`, `sections: [S-ALL]` 만 왔다. 백오피스에서 편성이 승인되면 **HOME 탭**에:

- `banners[]` — `bannerId`, `placement`(HOME_TOP·HOME_MIDDLE), `imageUrl`(예 `/api/v1/storefront/banner-images/BIMG-…` — 토큰 없이 GET, 1년 캐시), `altText`, `linkType`, `linkRef`, `sortOrder`
- `sections[]` — 테마 영역(`sectionId` 가 `T-` 로 시작, `kind` THEME·SEASON·WEEKLY_PICK, `title`·`subtitle`, `items` 첫 묶음 10개, `nextCursor`, `viewAllRef`)이 **앞에**, 마지막에 "전체 상품"(S-ALL)
- `layoutVersion` 이 1 이상

RANKING·NEW 탭에는 배너·테마가 붙지 않는다.

## 앱에 필요한 것
1. HOME_TOP 배너: 화면 위 가로 넘기기(sortOrder 순). HOME_MIDDLE: 첫 테마 영역 아래(없으면 그리지 않음)
2. 배너 누르기
   - `SECTION` → 같은 홈의 그 `sectionId` 영역으로 이동(또는 영역 전체 보기 `GET /storefront/sections/{sectionId}/items`)
   - `PRODUCT` → 상품 상세(`linkRef` = 상품 ID)
   - `CATEGORY` → 카테고리 목록(`linkRef` = 카테고리 ID)
   - `NONE` → 누를 수 없음
3. 테마 영역: 기존 섹션 카드 그대로(가로 목록 + 전체 보기). 영역 다음 묶음은 `layoutVersion` 을 같이 보내면 홈이 바뀌었을 때 409 `LAYOUT_VERSION_CHANGED` — 그때 홈을 다시 부른다
4. 배너가 없으면 배너 자리를 비운다(지금처럼)

## 하지 않는 것(1차)
할인율·쿠폰·특가 표시. 배너 노출 통계.

## 배포 상태
- 백엔드 구현·시험 끝. LAN 배포는 사용자 승인 뒤, 실제 배너는 백오피스 편성 화면이 생기고 승인돼야 나온다.
