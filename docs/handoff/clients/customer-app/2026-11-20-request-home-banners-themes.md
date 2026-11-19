# [pmart-backend → pmart-clients] 요청: 고객 앱 홈 — 배너·테마 영역·랭킹/신상품 탭 표시 (2026-11-20)

- 보낸 영역: platform(PC 1)
- 받는 앱: customer-app
- 근거: ADR-0075 ②(BO-05 일반 배너·상품 테마), 사용자 결정 2026-11-20 "탭 + 배너·테마"
- 계약: storefront-service **0.7.0**(홈 응답 모양은 0.6.0 과 같다 — 값이 채워지기 시작할 뿐). 탭은 0.6.0
- 사용자 지시 2026-11-20: "프론트 PC1 세션한테 이 작업해달라고 전달해줘" → 세션이 없어 이 문서로 남김(배너·테마·탭 고객 앱 작업 전부)

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

## 랭킹·신상품 탭 (0.6.0 — LAN 에서 켬, 2026-11-20)
LAN 정책값: 신상품 = 처음 게시 **14일** 안, 랭킹 = **30위**까지(사용자 "추천대로"). 이제 `GET /storefront/home` 의 `tabs` 에 `HOME`·`RANKING`·`NEW` 가 온다(값이 없으면 탭이 빠지고 404 `TAB_NOT_CONFIGURED` — 앱은 `tabs` 에 있는 탭만 그린다).

1. 탭 줄: `tabs` 순서대로. 탭을 누르면 `GET /api/v1/storefront/tabs/{RANKING|NEW}?mfcId=`(또는 `/home?tab=`) — 응답 모양은 홈과 같다(`sections` 하나: `S-RANKING` 또는 `S-NEW`, 배너 없음)
2. 랭킹: 항목 `badge: RANK`, `rank`(1부터) — 카드에 순위 숫자. 판매 많은 순 → 별점 점수. 30위에서 끝(`nextCursor` null)
3. 신상품: 항목 `badge: NEW`, 최신 게시 순. **홈 "전체 상품"·테마 영역 카드에도** 신규 기간 안이면 `badge: NEW` 가 붙는다 — 카드에 작은 NEW 표시
4. `badge: NONE` 이면 아무것도 안 그린다
5. LAN 데이터 주의: 시험 상품을 11-04·11-07 에 몰아 넣어서 11-20 에는 신상품이 229개, **11-22 부터는 1개**만 남는다(새 상품을 게시해야 다시 찬다). 랭킹은 LAN 주문이 있는 상품만(판매 0 은 빠짐) — 적게 보일 수 있다

## 하지 않는 것(1차)
할인율·쿠폰·특가 표시. 배너 노출 통계.

## 배포 상태
- **LAN 배포 2026-11-20**(storefront·게이트웨이 6e2f6d8a).
- LAN 예시 편성(layoutVersion 2, 2026-11-20): 배너 `B-WINTER-VEG`(HOME_TOP, SECTION → `T-WINTER-VEG`, 1984×793 JPEG), 테마 4개 `T-WINTER-VEG` "겨울 제철 채소"·`T-WEEKEND-MEAT` "주말엔 고기 파티"·`T-MORNING` "든든한 아침 한 상"·`T-QUICK-MEAL` "10분 완성 간편 한 끼". 배너는 곧 3개 더 붙는다(가로 넘기기 확인용). 랭킹·신상품 탭도 켜져 있다(14일·30위).
