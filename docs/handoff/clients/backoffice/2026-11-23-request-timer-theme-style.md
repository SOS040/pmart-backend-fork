# 요청 — 편성 테마 "남은 시간 표시" 모양 고르기(timerStyle)

보낸 영역: platform
받는 앱: backoffice (통합 백오피스 웹 — 진열 편성 화면)
작성: pmart-backend, 2026-11-23 — 고객 앱 BR-84, 사용자 "그 타이머 선택하는걸 백오피스에 기능을 넣고 싶고"
계약: **storefront-service 0.12.0**

## 바뀐 것
- 편성 초안 테마(`themes[]`, LayoutTheme)에 `timerStyle`: `LABEL`(라벨형 — 제목 위 분홍 글자 "⏱ 05:12:30 남음") · `BAND`(띠형 — 연분홍 띠, 오른쪽에 시·분·초 칸 + "남은 시간").
- `showTimer=true` 일 때만 뜻이 있다. 켜고 비우면 서버가 `LABEL`, 끄면 보내도 `null`.

## 부탁
1. 테마 편집에서 [남은 시간 표시] 를 켜면 **모양 고르기**(라벨형 / 띠형, 기본 라벨형)를 보인다. 끄면 숨긴다.
2. 미리보기가 있으면 두 모양을 그림으로(고객 앱 초안 `고객앱 ui/06_홈_타이머테마` C·D).
3. 초안 읽기(`GET /storefront/layout-drafts/{id}`)에도 `timerStyle` 이 온다 — 고칠 때 그대로 돌려보낸다.
- MD 탭 타이머(`tabs[].showTimer`)는 모양이 하나라 그대로다.

## 상태
- 2026-11-23: 백오피스 반영 확인 — pmart-clients 2202eca7(편성 남은 시간 모양 라벨형·띠형)·c0785e15(미리보기 색), LAN backoffice c0785e15(web-sync 맞음). 고객 앱 54a854f4. 홈 편성 8 에 두 모양 실제 사용(LABEL·BAND).
