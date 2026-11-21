# 요청 — 홈 프로모션 보충: 로딩 화면·팝업, 타이머 테마, MD 탭 (storefront 0.9.0)

- 보낸 영역: platform (PC 1)
- 작성: 2026-11-21 — 사용자 "처음 로딩 화면도 프로모션 설정 가능하게 / 팝업창 띄우기 / 탭 / 타이머 있는 테마(MD 특가, 실시간 인기 랭킹)", "진행순서대로"
- 서버 상태: **storefront 0.9.0 LAN 배포 2026-11-21**. 계약 `contracts/openapi/storefront-service.yaml`.

## 바뀐 것과 화면 요청
1. **처음 로딩 화면(SPLASH)**: 앱을 켤 때 `GET /storefront/banners?placement=SPLASH`(토큰 없음) — 기간 안 이미지가 있으면 로딩 화면에 보인다(없으면 지금 로딩 화면). 이미지 `imageUrl`(1년 캐시 가능 — 이미지 ID 마다 안 바뀜). 다음 실행을 빠르게 하려면 받아 둔 이미지를 다음 실행 때 쓰고 뒤에서 새로 받아도 된다.
2. **팝업(POPUP)**: 홈에 들어갈 때 `GET /storefront/banners?placement=POPUP` — 있으면 팝업(여럿이면 sortOrder 순 넘기기). 누르면 `linkType`/`linkRef`(SECTION·CATEGORY·PRODUCT). **"오늘 그만 보기"·"닫기"는 앱이 bannerId 로 기억**한다(서버 기록 없음).
   - 홈 응답 `banners` 와 `GET /storefront/banners`(placement 없음)는 이제 **HOME_TOP·HOME_MIDDLE 만** 준다 — 팝업·로딩 화면이 배너 줄에 섞이지 않는다.
3. **타이머 테마**: 홈 영역(Section)에 `endsAt`·`showTimer`. `showTimer=true` 면 영역 제목 옆에 남은 시간(예: "02:13:05 남음"). 0 이 되면 영역을 숨기고, 다음 홈 조회부터 서버도 뺀다.
4. **자동 랭킹 테마**: 편성의 "실시간 인기 랭킹" 같은 영역은 `kind: RANKING` 으로 온다(항목에 `rank`·badge RANK — 랭킹 탭과 같은 모양). 다음 묶음 없음(nextCursor null).
5. **MD 탭**: 홈 응답 `customTabs`([{tabKey `X-…`, title, endsAt, showTimer}] — 순서대로). 고정 탭(HOME·랭킹·신상품) 뒤에 붙인다. 탭 내용 `GET /storefront/custom-tabs/{tabKey}?mfcId=` → `{layoutVersion, tab, sections}`(영역 모양은 홈과 같다). 기간이 지나면 404 `TAB_NOT_FOUND` → 탭을 지우고 HOME 으로. 탭에 넣은 테마는 HOME 에서 빠진다.
6. 할인·특가 문구가 들어간 배너·테마가 올 수 있다(서버 문구 검사 완화). 할인가 칸(salePrice 등)은 0.8.0 그대로.

## 추가 — storefront 0.10.0(2026-11-21)
7. **배너 자동 넘김**: 홈 응답 `bannerIntervalSeconds`(초, null 이면 앱 기본값 — 예 4초). 맨 위 배너가 2개 이상이면 그 간격으로 자동으로 넘긴다(손으로 넘기면 잠깐 멈춤), **1개면 넘기지 않는다**.
8. **배너·테마 순서 섞기**: 홈 `sections` 에 `kind: BANNER` 영역이 올 수 있다 — `banners`(순서대로 넘기기)·`intervalSeconds`, `items` 는 빈 목록. **sections 를 받은 순서 그대로** 그리면 "배너, 테마, 배너" 순서가 된다. 맨 위 `banners`(HOME_TOP)와 HOME_MIDDLE 은 그대로.

