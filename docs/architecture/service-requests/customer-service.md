# customer-service 에 대한 요청 (auth 쪽에서)

## CU-1. 약관 동의 기능 (2026-11-09 기록)

- **요청한 곳**: auth-service — 동의 기능이 생기면 `require-consent=true` 로 켠다(`deploy/lan/.config-repo/auth-service.yml`).
- **지금**: false(사용자 결정 2026-10-30 — 동의 건너뜀). customer `ConsentsController` 501.
- **사용자 결정 필요**: 켜는 시점·약관 항목.
- **결정(2026-11-17 사용자 "추천대로")**: 지금 만들어 켠다. 필수 3(이용약관·개인정보 수집·이용·만 14세 이상) + 선택 1(마케팅 수신). 위치기반서비스는 현재 위치 기능 때.
- **구현(2026-11-17, LAN 배포 전)**: customer `ConsentService`(계약 0.3.0, V6 `customer_consent`·`_history`, `ConsentType` = TERMS_OF_SERVICE·PRIVACY_POLICY·AGE_OVER_14·MARKETING, 현행 버전 설정 `pmart.customer.consents.versions.*`) — `/me/consents`·`/me/consent-changes`(마케팅 켜고 끄기, 필수 끄기 422, 옛 버전 422)·내부 `/internal/accounts/{id}/consents`·`consent-changes`, `CustomerConsentChanged` 발행, 계정 상태 `purchasable` 에 필수 동의 반영(스위치 `consents.purchase-gate`). auth(계약 0.2.0, V5) — `require-consent=true` 면 신규 가입·약관 버전이 바뀐 기존 회원은 `AUTH_CONSENT`(`requiredConsents`·`optionalConsents` = `TYPE:version`), 완료는 `completions` 본문 `providerToken`+`consents`(BR-20 해소 — 토큰 없는 단계라 Google ID 토큰으로 본인 확인, 동의 뒤에만 프로필 사건). 고객 앱 안내 `docs/handoff/clients/customer-app/2026-11-17-notice-consents.md`. pmart-config(`customer-service.yml` 버전·`purchase-gate: false`, `auth-service-{lan,local}.yml` 주소)는 아직 적용 전. **서버는 준비, LAN 스위치는 고객 앱 반영 때까지 꺼 둔다**(`require-consent: false`·`purchase-gate: false`) — 켤 때는 둘을 함께 true.
