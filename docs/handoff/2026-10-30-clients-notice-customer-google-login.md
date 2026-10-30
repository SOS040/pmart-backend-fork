# 고객 앱 Google 로그인 연동 — auth-service 소셜 로그인 (ADR-0019)

- 작성: pmart-backend, 2026-10-30
- 대상: pmart-clients 고객 앱(Flutter)
- 계약 정본: `pmart-backend/contracts/openapi/auth-service.yaml` 의 `logins`·`tokens` 태그

## 1. 흐름
```
① 앱: Google Sign-In → Google ID 토큰(idToken) 받기
② 앱 → 게이트웨이 POST /api/v1/auth/social-logins   {provider: "GOOGLE", providerToken: <idToken>}
③ auth-service: Google 공개키로 서명·iss·aud·만료 검증 → 고객 계정 연결(없으면 생성) → PMart 토큰 발급
④ 앱: 응답 tokens.accessToken 으로 이후 API 호출, 만료 전에 /auth/tokens 로 갱신
```
- 지금은 **신규 가입도 바로 회원(`AUTH_MEMBER`)** 이다. 필수 약관 동의 단계는 아직 없다(사용자 결정). 동의 단계가 생기면 `state=AUTH_CONSENT`·`nextAction=COMPLETE_CONSENT` 가 올 수 있으니 분기만 미리 둔다.
- 카카오·네이버·애플은 아직 없다(`UNSUPPORTED_ACTION` 501).

## 2. Google 설정 (앱 쪽)
- Google Cloud 프로젝트: `pmart-123`
- **serverClientId(서버 클라이언트) = 웹 클라이언트 ID**: `293585219605-p6akml03k4lpi01agvgah41g2imo1aes.apps.googleusercontent.com`
  - Android 의 Google Sign-In 은 serverClientId 를 줘야 idToken 이 나오고, 그 idToken 의 `aud` 가 이 값이다. 백엔드는 이 값을 허용한다.
- 함께 받은 `installed` 유형 클라이언트(`293585219605-29edshi9builp9ic7rkcukgitu211fpu.apps.googleusercontent.com`)도 백엔드가 허용한다.
- **Android**: 같은 프로젝트에 Android OAuth 클라이언트(패키지 이름 + 서명 인증서 SHA-1)를 등록해야 로그인 창이 뜬다(없으면 `DEVELOPER_ERROR`/10). 이 클라이언트 ID 는 앱 설정에만 쓰고 백엔드에 따로 알릴 필요는 없다(idToken 의 aud 는 serverClientId).
- **iOS**: iOS OAuth 클라이언트를 만들어 앱의 clientId 로 쓰고, serverClientId 는 위 웹 클라이언트 ID 로 둔다. iOS idToken 의 aud 가 iOS 클라이언트 ID 로 오면 백엔드 허용 목록에 추가해야 하니 그 ID 를 알려 준다.
- client secret 은 앱·백엔드 어디에도 쓰지 않는다. 앱에 넣지 않는다.

## 3. API
| 할 일 | 요청 | 응답 |
|---|---|---|
| 로그인 | `POST /auth/social-logins` 헤더 `Idempotency-Key`(로그인 시도마다 새 UUID), `X-Source-System: customer-app`, `X-Device-Id`(설치마다 한 번 만든 UUID) / 본문 `{provider: "GOOGLE", providerToken: idToken, purpose?: GENERAL\|CHECKOUT}` | `{authRequestId, state, accountId, newAccount, tokens{accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, sessionId}, nextAction, occurredAt}` |
| 응답을 못 받았을 때 | 같은 `Idempotency-Key`·같은 기기로 다시 보낸다 → 같은 원인증 결과와 새 토큰. 또는 `GET /auth/social-logins/{authRequestId}` + `X-Device-Id`(상태만, 토큰 없음) | |
| 토큰 갱신 | `POST /auth/tokens` `{refreshToken}` + `Idempotency-Key`, `X-Source-System`, `X-Device-Id`(로그인 때와 같은 값) | 새 `TokenPair` |
| 내 세션 | `GET /auth/sessions/current` (Bearer) | `{principalType: "CUSTOMER", principalId: accountId, ...}` |
| 로그아웃 | `POST /auth/logouts` `{allDevices?}` | 204 |

토큰 규칙:
- access token(`typ=CUSTOMER`, `sub=accountId`)은 30분이다.
- refresh token 은 **한 번만 쓴다.** 쓸 때마다 새 값을 저장한다. 쓴 값을 다시 보내면 세션이 끊긴다(`TOKEN_REUSED`).
- 세션은 최대 30일이다. 지나거나 `TOKEN_INVALID`/`TOKEN_REUSED` 면 다시 Google 로그인한다.

## 4. 오류
| HTTP / code | 뜻 | 앱 처리 |
|---|---|---|
| 401 `PROVIDER_REJECTED` | idToken 이 무효(만료·aud 불일치·서명 오류) | 실패 안내 후 다시 로그인. 실패 횟수에 들어간다 |
| 423 `AUTH_RESTRICTED` | 같은 기기에서 15분 안에 5번 실패 → 10분 제한. 본문 `retryAfterSeconds`·`restrictedUntil`, 헤더 `Retry-After` | 남은 시간 안내. 앱이 시간을 새로 늘리지 않는다 |
| 503 `PROVIDER_UNAVAILABLE` | Google 확인 장애(실패 횟수에 넣지 않음) | 잠시 뒤 재시도 |
| 501 `UNSUPPORTED_ACTION` | Google 외 제공자 | 버튼 숨김 |
| 422 `CONFIG_REQUIRED` | 서버에 Google 클라이언트 ID 미설정 | 백엔드에 알림 |
| 403 `ACCOUNT_NOT_AVAILABLE` | 연결된 계정으로 로그인할 수 없음(내부 제한·탈퇴 완료). 구매만 막힌 상태(422 `ACCOUNT_NOT_PURCHASABLE`)와 다름(BR-27) | 안내 |

- 사용자가 Google 창에서 취소한 경우는 앱 안에서 처리하고 서버를 부르지 않는다(실패 횟수에 넣지 않는 것이 정책).
- 로그인 성공은 구매 자격 확정이 아니다(배송지·주문 검증은 각 단계에서 따로).

## 5. 환경
- LAN 게이트웨이: `http://D603-16:8080/api/v1`. **배포됨**(2026-10-30, auth-service 이미지 2f507dc).
- 로컬: auth-service 를 `PMART_AUTH_CUSTOMER_GOOGLE_CLIENTIDS=<위 두 ID, 쉼표>` 로 띄운다.
