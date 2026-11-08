# customer-service 에 대한 요청 (auth 쪽에서)

## CU-1. 약관 동의 기능 (2026-11-09 기록)

- **요청한 곳**: auth-service — 동의 기능이 생기면 `require-consent=true` 로 켠다(`deploy/lan/.config-repo/auth-service.yml`).
- **지금**: false(사용자 결정 2026-10-30 — 동의 건너뜀). customer `ConsentsController` 501.
- **사용자 결정 필요**: 켜는 시점·약관 항목.
