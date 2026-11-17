# [pmart-backend → pmart-clients] 알림: 백오피스 웹 담당 바뀜 + 라이더 백오피스 새 앱 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 받는 영역: backoffice(PC 2), mfc(PC 3), delivery(PC 5)
- 근거: 사용자 2026-11-17 — "MFC 백오피스를 PC2에서 PC3로, 이렇게 만들어진 라이더 백오피스는 PC2에서 PC5로 작업 이동할거야", "라이더 백오피스 … 앱 뼈대랑 로그인만 해줘"

## 바뀐 것

| 웹 앱 | 전 | 지금 | 화면이 부르는 백엔드 |
|---|---|---|---|
| `web/mfc-backoffice`(MFC 센터 관리자) | PC 2(11-16~17) | **PC 3** | MFC API — PC 3(같은 PC) |
| `web/rider-backoffice`(라이더 운영·관제, **새 앱**) | — | **PC 5**(뼈대·로그인은 PC 1, 2026-11-17) | 배송·라이더 API — PC 5(같은 PC) |
| `web/backoffice`·`web/hr-backoffice` | PC 2 | PC 2 그대로 | |
| 웹 공통 `web/core`·`web/ui` | PC 2 | **PC 2 그대로**(사용자 확정 2026-11-17) — PC 3·5 는 고칠 일이 생기면 PC 2 에 요청(네 앱 빌드를 한 곳에서 맞춘다) | |
| 로그인·세션·토큰 코드 | PC 1 | PC 1 그대로 | |

## 라이더 백오피스

- 주소: 개발 `http://localhost:5176`, LAN `https://192.168.0.40:8446`(PC 4, 다른 백오피스와 같은 곳). `app=rider-backoffice`, `X-Source-System rider-backoffice-web`, SSO 켬.
- 뼈대: 로그인·조용한 로그인 확인·세션·메뉴 자리 4개(관제 / 라이더 찾기 / 배송 찾기 / 접수 건 — 모두 "준비 중"). 앱 문서 pmart-clients `docs/apps/rider-backoffice-web.md`.
- 계획·남은 사용자 결정(기존 백오피스의 라이더 운영 화면을 옮길지 등): `pmart-backend/docs/delivery/rider-backoffice-plan.md`.
- LAN 반영: 담당 PC 는 PR 만. **빌드·올리기는 PC 1** — 병합한 main 에서 `npm run build:lan -w web/<앱>` → `lan.sh publish-<앱>`(mfc-backoffice·rider-backoffice 모두).
