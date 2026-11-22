# 회신 — BR-84 타이머 테마 머리 모양(timerStyle)

보낸 영역: platform
받는 앱: customer-app
요청: pmart-clients `docs/handoff/customer-app/2026-11-23-backend-timer-theme-style.md`(BR-84)
작성: pmart-backend, 2026-11-23 — 사용자 "그 타이머 선택하는걸 백오피스에 기능을 넣고 싶고"
계약: **storefront-service 0.12.0**(`contracts/openapi/storefront-service.yaml`)

## R1 — 반영
- 편성 테마 `LayoutTheme.timerStyle`: `LABEL`(제목 위 분홍 글자, 기본) · `BAND`(연분홍 띠 + 시·분·초 칸).
- **`showTimer=true` 일 때만 값이 있다** — 켜져 있는데 비우면 `LABEL`, `showTimer=false` 면 보내도 `null` 로 저장·응답.
- 홈 `sections[]`·MD 탭 내용 `GET /storefront/custom-tabs/{tabKey}` 의 `sections[]`(Section)에 같은 `timerStyle`. 타이머가 없는 영역은 `null` — 앱은 없거나 null 이면 LABEL(요청서대로).
- 이미 게시된 편성(지금 LAN 진열)은 값이 없으므로 타이머 테마도 `LABEL` 로 내려간다.

## Q1 — MD 탭 타이머
- 범위 밖으로 둔다(동의). `CustomTab.showTimer` 는 그대로, 모양 값 없음.

## 백오피스
- 편성 화면의 테마 편집 [남은 시간 표시] → 모양 고르기는 통합 백오피스(PC 2)에 요청했다(`docs/handoff/clients/backoffice/2026-11-23-request-timer-theme-style.md`).
- LAN 확인용으로 띠형 테마가 필요하면 PC 1 이 시험 편성을 하나 넣을 수 있다(말해 주면).

## 시험
- storefront `check`: `LayoutIT`(타이머 테마 BAND 게시·홈 영역 BAND·타이머 없는 테마 null)·`LayoutRulesTest.timerStyleOnlyWithTimerDefaultLabel` 통과. `HomeFlowIT.rankingSignalsFromOrdersAndReviews` 1건 실패는 이 변경 전부터(main, 2026-11-22 기록).
