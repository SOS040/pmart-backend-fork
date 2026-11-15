# [pmart-backend → pmart-clients] 회신: MFC 백오피스 기반 — 빌드 올림·시험 계정 (2026-11-15)

- 보낸 영역: platform
- 원 문서: pmart-clients `docs/handoff/mfc-backoffice/2026-11-15-backend-mfc-backoffice-base.md`

## 1. 빌드 올림
- `web/mfc-backoffice/dist`(`index-DUFxsA45.js`·`index-BtcOg322.css`)를 `lan.sh publish-mfc-backoffice` 로 `https://192.168.0.40:8445` 에 올렸다(안내 페이지 대신 앱). `http://…:8082` 는 8445 로 넘긴다.

## 2. 센터 관리자 시험 계정
- **`claude-mfc-sup`**(Keycloak 아이디 `claude-mfc-sup@pmart.com` — 2026-11-15 부터 로그인 화면에서 `claude-mfc-sup` 만 쳐도 된다): 역할 `MFC_SUPERVISOR`, 센터 `MFC-SEED-01`. 비밀번호는 PC 1 `deploy/lan/.secrets/keycloak-claude-mfc-sup-password`(파일로만 — 채팅·로그에 붙이지 않는다). 이제 시험 스크립트가 돌아도 바뀌지 않는다.
- **센터 두 곳 계정은 지금 못 만든다** — LAN 에 MFC 가 `MFC-SEED-01` 하나뿐이다(`GET /centers?type=MFC`). 센터 고르기 시험은 목 모드로. 두 번째 MFC 를 LAN 에 만들지는 정해지면 알림.
- 작업자만 있는 계정 확인용: `claude-mfc-wrk`(`MFC_WORKER`, 같은 센터) — 비밀번호 파일이 필요하면 요청.

## 3. 메모
- 메뉴 화면을 채울 PC 는 사용자가 2026-11-16 정한다.
- 로그인·세션 코드는 PC 1 만 고친다(`../all/2026-11-15-notice-login-code-pc1-only.md`) — 이 앱의 `src/features/session` 도 같다.
