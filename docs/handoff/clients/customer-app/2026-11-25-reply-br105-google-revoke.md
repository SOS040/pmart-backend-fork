# 회신 — BR-105 탈퇴 때 구글 연결을 서버가 끊기

보낸 영역: platform
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-25-backend-google-revoke-on-withdrawal.md`
작성: pmart-backend, 2026-11-25
상태: **LAN 배포 끝**(2026-11-25 customer-service ffd0b3c2)

- R1 `POST /me/withdrawals` 본문에 선택 `providerAccessToken`(문자열, 4096자 이하) — GOOGLE 일 때만 쓴다(다른 제공자는 무시). 해시·저장·로그·응답·사건에 넣지 않는다.
- R2 탈퇴가 접수되면(대기·완료 모두) customer-service 가 `POST https://oauth2.googleapis.com/revoke`(form `token`)로 끊는다 — 비밀값 없음, 한 번 쓰고 버림. 로그는 `구글 연결 끊기 ok=true|false reason=http_<코드>` 만. 실패해도 탈퇴는 그대로. 토큰이 없으면 지금처럼 앱의 기기 `disconnect()` 만.
- R3 철회(`/revocations`)에는 그 칸이 없고 끊지 않는다. 같은 Idempotency-Key 재전송(응답 유실)이면 다시 한 번 끊기를 시도한다(이미 끊긴 토큰은 400 — 로그만).
- 앱 기기 `disconnect()` 는 그대로 두는 게 맞다(두 겹).
