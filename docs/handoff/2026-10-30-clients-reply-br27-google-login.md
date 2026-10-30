# [pmart-backend → pmart-clients] BR-27 고객 Google 로그인 확인 요청 회신

- 원 요청: `pmart-clients/docs/handoff/2026-10-30-backend-customer-google-login.md`
- 기준: pmart-backend `main` (이 회신과 같은 커밋), `contracts/openapi/auth-service.yaml`, ADR-0019

## 1. 이용 불가 계정 오류 코드 (BR-27) — 수용: 계약 보완
- **실제 응답은 `403 ACCOUNT_NOT_AVAILABLE`** 이다. Google 인증은 맞지만 연결된 PMart 계정으로 **로그인 자체를 할 수 없을 때**(내부 제한·탈퇴 완료) 낸다. 토큰은 주지 않는다.
- 계약에 이 응답이 빠져 있었다. `POST /auth/social-logins` 에 `403 ACCOUNT_NOT_AVAILABLE` 을 추가했다(이 회신과 같은 커밋).
- `422 ACCOUNT_NOT_PURCHASABLE` 는 다른 사실이다. **로그인은 되지만 구매가 막힌 상태**(탈퇴 대기 등)이다. 이 판정은 고객 계정 정본(customer-service)과 주문 단계에서 하고, 로그인 응답으로는 오지 않는다. 지금은 customer-service 가 없어 나오지 않는다.
- `422 REQUIRED_CONSENT_MISSING` 은 동의 단계(`completions`)용이다. 동의 단계를 쓰지 않으므로 지금은 나오지 않는다.
- 클라이언트 처리: 403 `ACCOUNT_NOT_AVAILABLE` 는 "이 계정으로 로그인할 수 없어요"(재시도 안내 없음)로 보이면 된다. 422 `ACCOUNT_NOT_PURCHASABLE` 은 나중에 주문 쪽 안내로 따로 다룬다. 지금처럼 같은 문구로 묶어 둬도 문제는 없다.

## 2. 클라이언트 처리 확인
- **501 `UNSUPPORTED_ACTION`**: 맞다. 결과 불명이 아니라 확정 거절이다. 계약에 501 응답과 설명도 추가했다. 결과 불명은 `503`(`PROVIDER_UNAVAILABLE`/`UNKNOWN_RESULT`)뿐이다.
- **앱 시작 때마다 refresh 회전 1회**: 괜찮다. 회전 횟수 제한은 없다. 다만 두 가지를 지켜 달라.
  1. **동시에 두 번 갱신하지 않는다.** 앱 시작 직후 여러 요청이 한꺼번에 401 을 받아도 갱신은 하나만 하고 결과를 나눠 쓴다. 같은 refresh token 이 두 번 오면 서버는 탈취로 보고 세션을 끊는다(`TOKEN_REUSED`).
  2. **새 refresh token 을 받으면 바로 보안 저장소에 쓴다.** 갱신 응답을 받은 직후 앱이 종료되어 새 값을 못 쓰면, 다음 실행 때 옛 값으로 갱신하게 되어 `TOKEN_REUSED` 로 다시 로그인해야 한다. 이 경우는 Google 로그인 화면으로 보내면 된다.
  - 참고: access token(30분)도 보안 저장소에 두면 앱 시작 때 회전 없이 `GET /auth/sessions/current` 가 바로 성공할 수 있다. 지금 방식도 정상이라 선택 사항이다.
- **Google 창 취소·중단·`DEVELOPER_ERROR` 를 서버에 보내지 않는 것**: 맞다. 서버 실패 횟수는 서버가 받은 무효 토큰(`PROVIDER_REJECTED`)만 센다.

## 3. iOS 미진행 — 확인
- 클라이언트 ADR-0002 확인했다. iOS 클라이언트·허용 목록 추가는 하지 않는다. 백엔드 안내 §2 의 iOS 문구는 "필요할 때"로 남겨 둔다.

## 4. 참고
- LAN 에 배포됐다(auth-service 2f507dc, 게이트웨이 `http://D603-16:8080/api/v1`). 실제 기기로 로그인해 보고 결과(성공·오류 code)를 알려 주면 서버 로그와 맞춰 보겠다.
- Android 로그인 창이 뜨려면 `pmart-123` 프로젝트에 앱 패키지 이름과 **디버그 서명 SHA-1** 로 Android OAuth 클라이언트가 등록돼 있어야 한다(확인한 지문을 등록했는지 확인 부탁).
