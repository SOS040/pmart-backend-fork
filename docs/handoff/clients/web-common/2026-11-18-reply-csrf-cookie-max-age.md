# [pmart-backend → pmart-clients] 회신: CSRF 쿠키 쌓임 431 — C1(Max-Age) 반영 (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: web-common(`web/core` 직원 로그인 — 백오피스 4개)
- 대상: pmart-clients `docs/handoff/web-common/2026-11-18-backend-csrf-cookie-pileup.md`(C1·C2)
- 계약: employee-auth-service **0.12.1**(info.description 의 쿠키 줄 — 설명만, API 모양 그대로)

## 한 것 — C1
- **CSRF 쿠키** `__Secure-pmart_ea_csrf_{sessionId}` 에 `Max-Age` = 세션 최대 길이(12시간 — `session-max-age`, 세션은 그보다 길지 않다).
- **refresh 쿠키** `__Secure-pmart_ea_rt` 에 `Max-Age` = 그 refresh token 의 만료까지(티켓 교환·갱신마다 새로 심을 때 다시 정함).
- 이름·Path·SameSite·Secure·읽는 법은 그대로 — **앱 변경 없음**. 로그아웃은 예전처럼 Max-Age=0 으로 지운다.
- 원인 진단은 맞았다: 둘 다 브라우저 세션 쿠키(Max-Age 없음)라 로그아웃 없이 끝난 세션의 CSRF 쿠키(Path=/)가 같은 호스트 `192.168.0.40`(포트만 다른 백오피스 4개)의 모든 요청에 쌓였다.
- 시험: `RefreshCookieIT`(CSRF Max-Age=43200, refresh Max-Age 1~43200, 갱신 쿠키에도 Max-Age) — employee-auth 단위 19·통합 41 통과.

## 배포 뒤 할 일
- 이미 쌓인 옛 쿠키(Max-Age 없음)는 저절로 안 지워진다 → PC 2·3·5 와 쓰는 사람마다 **백오피스 사이트 데이터 한 번 지우기**(Chrome: 주소창 자물쇠 → 사이트 설정 → 데이터 삭제, 또는 `192.168.0.40` 쿠키 삭제). 그 뒤로는 쌓이지 않는다.
- LAN 배포 여부는 PC 1 일지(`docs/worklog/platform/2026-11-18.md`)에 적는다.

## 요청 머리말 한도(물어본 것)
- 따로 올리지 않았다(C1 로 쌓이지 않게 하는 것이 해결). 게이트웨이(Spring Cloud Gateway — Netty)의 기본 요청 머리말 한도는 8KB 안팎으로 알고 있다 — LAN 에서 정확한 값을 재 보지는 않았다. 앞단 Nginx(웹 4개)는 정적 파일만 내주고 API 는 게이트웨이 8443 으로 바로 간다.
