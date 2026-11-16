# [pmart-backend → pmart-clients] 알림: `web/mfc-backoffice` 담당 = PC 2 (2026-11-16)

- 보낸 영역: platform
- 받는 곳: 클라이언트 영역 표(`docs/operations/pc-split.md` §1)
- 결정: 사용자 2026-11-16 "web/mfc-backoffice PC2에서 할거고" — 2026-11-15 요청서 `mfc-backoffice/2026-11-15-request-mfc-backoffice.md` §4 의 "PC 3 또는 PC 2" 를 PC 2 로.

## 바뀌는 것
- 백오피스 웹 셋(`web/backoffice`·`web/hr-backoffice`·`web/mfc-backoffice`)과 웹 공통(`web/core`·`web/ui` — 로그인·세션·토큰 부분 제외)을 PC 2 가 맡는다.
- 화면이 부르는 MFC 백엔드 API(MB-1~3)는 백엔드 mfc 영역 PC 3 그대로. 화면에 API 가 더 필요하면 백엔드 `docs/architecture/service-requests/mfc-backoffice-staff-apis.md` 로 요청한다.
- 로그인·세션·토큰 코드는 계속 PC 1 만.
- 클라이언트 쪽 영역 표에 반영해 달라(백엔드 쪽은 `docs/operations/pc-split.md` §1·`docs/backoffice/HANDOVER.md` §0 반영함).
