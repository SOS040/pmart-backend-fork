# customer-service 에 대한 요청 (auth 쪽에서)

## CU-1. 약관 동의 기능 (2026-11-09 기록)

- **요청한 곳**: auth-service — 동의 기능이 생기면 `require-consent=true` 로 켠다(`deploy/lan/.config-repo/auth-service.yml`).
- **지금**: false(사용자 결정 2026-10-30 — 동의 건너뜀). customer `ConsentsController` 501.
- **사용자 결정 필요**: 켜는 시점·약관 항목.
- **결정(2026-11-17 사용자 "추천대로")**: 지금 만들어 켠다. 필수 3(이용약관·개인정보 수집·이용·만 14세 이상) + 선택 1(마케팅 수신). 위치기반서비스는 현재 위치 기능 때.
