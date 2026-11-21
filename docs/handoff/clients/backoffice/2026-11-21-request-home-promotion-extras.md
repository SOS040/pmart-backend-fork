# 요청 — 진열 편성 화면 보충: 팝업·로딩 화면 배너, 타이머 테마, 자동 랭킹 테마, MD 탭 (storefront 0.9.0)

- 보낸 영역: platform (PC 1)
- 작성: 2026-11-21 — 사용자 "처음 로딩 화면도 프로모션 설정 가능하게 / 팝업창 띄우기 / 탭 / 타이머 있는 테마(MD 특가, 실시간 인기 랭킹)" → "1.(가) MD 가 탭을 만든다, 2. 특가 문구 금지 풀어줘, 진행순서대로"
- 서버 상태: **storefront 0.9.0 LAN 배포 2026-11-21**. 계약 `contracts/openapi/storefront-service.yaml`(LayoutDraftBody·LayoutBanner·LayoutTheme·LayoutTab).
- 이어지는 것: `2026-11-20-request-home-banners-themes.md`(편성 화면 1차).

## 편성 본문에 생긴 것
1. **배너 자리** `placement` 에 `POPUP`(앱 첫 화면 팝업)·`SPLASH`(처음 로딩 화면). 이미지·대체 글·연결·기간은 HOME 배너와 같다. 고객 홈 배너 줄에는 나오지 않는다.
2. **테마** `showTimer`(남은 시간 표시 — `endsAt` 필수, 없으면 `TIMER_NEEDS_END`). 끝 시각이 지나면 서버가 홈에서 자동으로 뺀다.
3. **자동 랭킹 테마** `kind: RANKING_AUTO` — 상품을 넣지 않고 `autoSize`(1~30)만. 랭킹 탭과 같은 순위 상위 N 개가 자동으로 들어간다(예: "실시간 인기 랭킹"). 상품을 넣거나 autoSize 가 없으면 `INVALID_AUTO_THEME`. 일반 테마는 상품이 1개 이상이어야 한다(`EMPTY_THEME` — 전에는 계약 minItems).
4. **MD 탭** 본문 `tabs`(최대 5): `tabKey`(`X-…`), `title`(20자), `sectionIds`(이 탭에 보일 테마 — 순서), `sortOrder`, 기간, `showTimer`(endsAt 필수). 탭에 넣은 테마는 **HOME 에서 빠진다**. 없는 테마 `UNKNOWN_SECTION`, 한 테마를 두 탭에 넣거나 tabKey 중복이면 `DUPLICATE_ID`.
5. **문구 검사 완화** — 할인·특가·쿠폰·세일·1+1 문구 허용(ADR-0082 쿠폰·기간 할인이 켜짐). 근거 없는 "최저가"·"신선 보장"만 `PROMOTION_NOT_ALLOWED`. 화면의 "할인 문구 쓰면 안 돼요" 안내는 걷어 주세요.

## 화면 제안
- 배너 자리 고르기에 "팝업"·"로딩 화면" 추가(미리보기는 휴대폰 전체 화면 비율).
- 테마 편집: "남은 시간 표시" 켜기(켜면 끝 시각 필수), 종류 "자동 랭킹"이면 상품 고르기 대신 "상위 몇 개"(1~30).
- 탭 편집: 탭 이름·기간·남은 시간 표시, 넣을 테마 고르기(다른 탭에 들어간 테마는 고를 수 없게).
- 제출 422 details 의 새 사유 넷(TIMER_NEEDS_END·INVALID_AUTO_THEME·EMPTY_THEME·UNKNOWN_SECTION) 문구.

## LAN 시험 데이터
- 시험 배너 2개("주말엔 고기 파티", "10분 완성 간편 한 끼" — HOME_TOP) 를 넣은 편성 초안 `LD-7a5244aa-…` 을 제출하고 진열 승인 요청 `AP-4a6848b9-…` 을 올렸다 — **DISPLAY_APPROVER 승인 대기**(백오피스 승인 화면).

## 추가 — storefront 0.10.0(2026-11-21, 사용자 "넘김 간격 MD 가 편성 가능하도록, 순서 섞기도 추천대로")
- **배너 넘김 간격** 편성 `bannerIntervalSeconds`(2~30초, 비우면 앱 기본값 — `INVALID_INTERVAL`). 맨 위 넘기기·배너 묶음 모두.
- **순서 섞기**: 배너 자리 `HOME_INLINE` + `groupId`(`G-…` — 같은 묶음은 한 자리에서 넘기기, 다른 자리에 groupId 를 넣거나 HOME_INLINE 에 빼면 `INVALID_GROUP`). 편성 `homeOrder` = 배너 묶음 groupId·테마 sectionId 를 보일 순서대로(최대 40). 없는 것·MD 탭에 넣은 테마 `UNKNOWN_SECTION`(field `homeOrder[i]`), 중복 `DUPLICATE_ID`, HOME_INLINE 묶음이 homeOrder 에 없으면 `UNPLACED_GROUP`(field `homeOrder`). 비우면 예전 순서(테마 sortOrder).
- 화면 제안: 편성 화면에 "홈 순서" 목록(배너 묶음·테마를 위아래로 옮기기 — 칸반·끌어 옮기기는 나중, 지금은 위/아래 단추나 순번이면 충분), 배너 편집에 "홈 중간 묶음" 자리와 묶음 고르기, 편성 위쪽에 "넘김 간격(초)".

