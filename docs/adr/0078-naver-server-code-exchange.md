# ADR-0078 네이버 로그인 — 서버가 인가 코드를 ID 토큰으로 교환 (ADR-0055 결정 3·ADR-0071 일부 대체)

- 상태: **승인** (2026-11-20)
- 사용자 결정 2026-11-20: BR-69 에 "응 해줘"(추천안 — 교환 전용 API, 이후 단계는 지금처럼 id_token)
- 근거: pmart-clients `docs/handoff/customer-app/2026-11-20-backend-naver-token-exchange.md`(BR-69)
- 대체: ADR-0055 결정 3·ADR-0071 의 "서버는 인가 코드 교환·client secret 을 쓰지 않는다" — **네이버에 한해** 바꾼다. Google·카카오는 그대로.

## 배경
- 네이버 OIDC 설정 `token_endpoint_auth_methods_supported` = `["client_secret_post"]`(2026-11-20 확인). PKCE(S256)는 받지만 secret 을 대신하지 않는다.
- 앱에서 secret 없이 토큰 교환 → 401 `invalid_client`. 앱에는 secret 을 둘 수 없다(클라이언트 절대 규칙).

## 결정
1. **교환 전용 API**: 고객 `POST /auth/naver-token-exchanges`(auth-service), 라이더 `POST /rider-auth/naver-token-exchanges`(rider-auth-service).
   - 본문 `authorizationCode`·`codeVerifier`·`redirectUri`(·`state`). 서버가 Client ID·Secret 으로 네이버 토큰 엔드포인트에 교환하고 **id_token 만** 돌려준다.
   - 로그인·동의·휴대폰 확인·탈퇴 재인증 API 는 그대로 id_token(`providerToken`)을 받는다 — 코드 한 번 쓰기와 이후 단계의 토큰 재요구가 부딪히지 않는다.
2. `redirectUri` 는 그 앱의 콜백 중계 주소(설정)와 같아야 한다.
3. 같은 Idempotency-Key 재전송은 처음 결과(id_token)를 다시 준다(코드 이중 교환 방지). 결과는 요청 기록(`request_record`)에 남는다 — id_token 은 짧게 유효(네이버 1시간)하고 본인 기기의 같은 키 재전송에만 돌려준다.
4. 오류: 네이버 거절 401 `PROVIDER_REJECTED`, 응답 없음·5xx 503 `PROVIDER_UNAVAILABLE`, secret 없음 422 `CONFIG_REQUIRED`.
5. Client Secret 은 pmart-config `<서비스>-lan.yml` 의 `{cipher}`(원본 `deploy/lan/.secrets/`, git 제외). 앱·빌드 설정·로그에 두지 않는다.

## 결과
- 앱: flutter_appauth `authorizeAndExchangeCode` → `authorize`(코드만) → 이 API → 받은 id_token 으로 기존 로그인.
- 서버가 네이버 access/refresh token 을 받지만 저장·사용하지 않는다(버림).
