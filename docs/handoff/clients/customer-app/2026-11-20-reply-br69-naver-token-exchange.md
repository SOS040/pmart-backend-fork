# 회신 — BR-69 네이버 로그인 토큰 교환 (auth 0.10.0·rider-auth 0.3.0, ADR-0078)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-20
- 답하는 것: pmart-clients `docs/handoff/customer-app/2026-11-20-backend-naver-token-exchange.md` R1~R5 (사용자 "응 해줘")
- 상태: **LAN 배포됨 2026-11-20 15:29 KST** — auth-service·api-gateway(PC 4), rider-auth-service(PC 5) 61e969d2, pmart-config d9f262f

## 정한 방식 — 교환 전용 API (로그인 API 는 그대로 id_token)
- 고객 `POST /api/v1/auth/naver-token-exchanges`, 라이더 `POST /api/v1/rider-auth/naver-token-exchanges` (토큰 없이, 게이트웨이 공개 경로).
- 헤더 `Idempotency-Key`·`X-Source-System`, 본문 `{authorizationCode, codeVerifier, redirectUri, state?}` → 200 `{idToken}`.
- 서버가 Client Secret 으로 네이버 `oauth2/token` 에 교환하고 **id_token 만** 준다. 앱은 그 id_token 을 지금처럼 `providerToken` 으로 쓴다 — `social-logins`·`completions`·`identity-verifications`·`confirmations`·탈퇴 재인증(`/me/withdrawals`) 모두 그대로(R2 해결: 코드 한 번 쓰기와 부딪히지 않음).

## R1~R5
- **R1**: 위 API. `redirectUri` 는 인가 요청에 쓴 값 그대로 — 고객 `http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/customer`, 라이더 `…/naver/rider`. 다르면 400 VALIDATION_FAILED(네이버를 부르지 않음).
- **R2**: 서버가 기억하지 않는다 — 앱이 받은 id_token 을 메모리에 두고 다음 단계에 다시 보낸다(구글·카카오와 같음).
- **R3**: 같은 `Idempotency-Key`·같은 본문 재전송은 처음 받은 id_token 을 다시 준다(네이버를 다시 부르지 않음). 같은 키·다른 본문 409. 실패한 요청은 기록되지 않아 같은 키로 다시 시도할 수 있다.
- **R4**: 네이버 거절(`invalid_grant`·`invalid_client` 등) 401 `PROVIDER_REJECTED`, 응답 없음·5xx 503 `PROVIDER_UNAVAILABLE`, 서버에 Secret 없음 422 `CONFIG_REQUIRED`. 이 API 실패는 로그인 실패 횟수(기기 제한)에 세지 않는다.
- **R5**: 라이더 앱도 같은 방식 — `…/rider-auth/naver-token-exchanges`.

## 확인한 것
- LAN 게이트웨이로 두 API 에 틀린 `redirectUri` → 400(Secret·설정이 들어가 422 아님). 실제 교환은 앱의 실제 코드로 확인해 주세요.
- 앱 빌드 설정의 `NAVER_CLIENT_SECRET`(고객 `apps/customer_app/.secrets/customer.env`, 라이더 PC 5 `.secrets/rider.env`)은 서버로 옮겼다(`deploy/lan/.secrets/`) — 앱 쪽 파일에서는 지워도 된다.
