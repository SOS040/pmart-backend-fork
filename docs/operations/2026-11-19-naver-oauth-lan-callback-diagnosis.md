# 네이버 소셜 로그인 LAN 콜백 오류 진단 (2026-11-19 저녁)

- 증상(사용자): 고객 앱에서 네이버 로그인 → 정보 제공 동의까지 됐는데 그 뒤 로그인이 끝나지 않음. `nid.naver.com` 일반 접속 오류 화면이 보임.
- 문서 출처: 사용자가 가져온 일반 점검표(아래 "원문") — 2026-11-20 PC 1 이 우리 구조·LAN 기록으로 확인한 결과를 앞에 붙임.
- 관련: ADR-0071(카카오·네이버도 ID 토큰 검증만), 회신 `docs/handoff/clients/customer-app/2026-11-19-reply-br66-naver-callback-relay.md`(BR-66 콜백 중계), auth-service 0.5.0 `OAuthCallbacksController`·`OAuthCallbackRules`.

## 0. 우리 구조 — 원문과 다른 점
원문은 서버가 OAuth2 클라이언트(Spring Security `/login/oauth2/code/naver`, client secret, 서버가 토큰 교환)인 경우를 가정한다. **PMart 는 그렇지 않다**(ADR-0071 결정 2).

```text
고객 앱(AppAuth 등, PKCE S256, client secret 없음)
  → 네이버 authorize(redirect_uri = http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/customer)
  → 브라우저가 게이트웨이 콜백 경로로 이동
  → auth-service 가 받은 쿼리 그대로 302 → com.pmart.customer://oauth/naver?code=…&state=…  (서버는 코드를 읽지·저장하지·교환하지 않음)
  → 앱이 PKCE verifier 로 네이버 토큰 엔드포인트에서 직접 id_token 받기(redirect_uri 는 위 http 주소 그대로)
  → 앱 → POST /api/v1/auth/social-logins {provider: NAVER, providerToken: id_token}
  → auth-service 가 네이버 JWKS 로 서명·iss·aud(= Client ID)·exp 검증 → 약관(AUTH_CONSENT) → completions → PMart 토큰
```
- 원문 4.4 의 `/login/oauth2/code/naver`·`{baseUrl}` 확장·Spring Security 콜백 설정은 우리와 무관하다. 콜백 경로는 게이트웨이 공개 경로(`api-gateway.yml` public-paths)와 auth permit-paths 에 들어 있다.
- 서버에는 client secret 이 없다(넣지 않는다). 토큰 교환 실패는 서버 로그에 남지 않는다 — 앱 쪽에서 봐야 한다.

## 1. LAN 기록으로 확인한 것 (2026-11-20, PC 1)
출처: PC 1 Prometheus `http_server_requests_seconds_count`(auth-service), 로그 ES(pmart-logs-*), auth DB 읽기만. 시각은 KST.

| 시각(11-19) | 기록 | 뜻 |
|---|---|---|
| 21:58 | 콜백 고객·라이더 302 1번씩 | 배포 직후 PC 1 확인 호출 |
| 22:03 | 고객 콜백 302 +1 | 확인 호출 또는 첫 시도(구분 불가) |
| 22:20 | pmart-config 0752d9f — 네이버 Client ID(허용 aud) 넣음 | 그 전 NAVER 로그인 요청은 422 CONFIG_REQUIRED 였을 것(20:59 의 422 2건) |
| 22:20:44 | social-logins 거절 KAKAO·NAVER·GOOGLE 같은 초 | 설정 뒤 PC 1 가짜 토큰 확인(실사용 아님) |
| **22:25, 22:42** | **고객 네이버 콜백 302 — 서버 도착·앱 주소로 넘김** | 폰 브라우저 → LAN 게이트웨이까지 정상 |
| 22:28 | 새 계정 AUTH_CONSENT(제공자 **KAKAO**), completions 없음 | 카카오는 서버 검증까지 됐고 약관 단계에서 멈춤 |
| 22:42 이후 | **NAVER `POST /auth/social-logins` 없음** | 콜백 뒤 앱이 서버에 로그인 요청을 보내지 않음 |

- auth DB `social_identity`: GOOGLE 8, KAKAO 1, **NAVER 0** — 네이버로 서버 로그인까지 간 적이 한 번도 없다.

## 2. 결론 — 어디서 멈췄나
- 원문 후보 4(폰 → LAN IP 접근)·5(게이트웨이 라우팅·보안)는 **아니다** — 22:25·22:42 콜백이 서버에 닿아 302 가 나갔다.
- 원문 후보 1(네이버가 http·사설 IP 콜백을 거부)도 **적어도 그 두 번은 아니다** — 네이버가 그 주소로 보냈다. `nid.naver.com` 오류 화면이 뜬 시도는 서버 기록이 없으므로 네이버 단계에서 멈춘 것(콘솔 Callback URL 과 앱 `redirect_uri` 의 글자 차이, PKCE 파라미터, 네이버 앱 설정 등 — 원문 4.1·4.2 로 확인).
- **콜백이 닿은 두 번은 그 뒤 앱 쪽에서 멈췄다**: ① 앱이 `com.pmart.customer://oauth/naver` 를 받았는지(매니페스트 scheme·host·path, 앱이 떠 있는지) ② 받은 code 로 네이버 토큰 교환이 되는지(PKCE verifier·`redirect_uri` 가 authorize 때와 같은지 — 다르면 네이버가 거절) ③ 받은 `id_token` 으로 `POST /auth/social-logins` 를 보냈는지. 서버에는 ③ 이 오지 않았다.
- 카카오 쪽 별건: 22:28 새 계정이 약관(AUTH_CONSENT) 뒤 completions 를 보내지 않았다 — 앱 약관 화면 흐름 확인 필요.

## 3. 다음 확인(앱 쪽 — 고객 앱 세션)
1. 네이버 시도 때 앱 로그(AppAuth 결과·오류)와 폰 브라우저 마지막 주소: 302 의 `com.pmart.customer://…` 로 앱이 열렸는지.
2. 토큰 교환 요청의 `redirect_uri` 가 authorize 때와 **글자 하나까지** 같은지(`http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/customer`), `code_verifier` 가 같은 시도의 것인지.
3. 교환 응답에 `id_token` 이 있는지(scope `openid`). 없으면 네이버 앱 설정(OIDC) 확인.
4. 서버 확인은 PC 1 에 시각만 알려 주면 된다 — 위와 같은 기록(Prometheus·로그 ES·auth DB)으로 서버 도착 여부를 본다. 인가 코드·토큰·세션 쿠키는 보내지 않는다.

## 4. 2026-11-20 폰 연결 진단 뒤 (갱신)
- 09:19 시도: 콜백 09:19:48 서버 302 → **앱 수신 화면 실행 없음**(adb logcat) → 원인 = 네이버가 스크립트로 보낸 이동에 이어진 302 의 앱 주소를 Chrome 이 안 엶. **고침: auth 0.7.0 콜백 = "PMart 앱으로 돌아가기" 페이지**(LAN 배포 1d3df48a).
- 09:46·09:48 시도: Chrome 이 앱 주소로 **앱을 엶**(RedirectUriReceiverActivity) → `W/AppAuth: No stored state - unable to handle response`. 원인 = 고객 앱 MainActivity `android:taskAffinity=""` — 요청을 시작한 화면과 응답을 받는 화면이 다른 task. **앱에서 그 줄을 빼야 함**: 요청서 `docs/handoff/clients/customer-app/2026-11-20-request-naver-login-task-affinity.md`(라이더·MFC 앱도 같은 줄).
- 네이버 콘솔: Callback URL = 앱 `redirect_uri`(맞음). 연결 끊기 Callback URL 은 로그인 주소와 같으면 안 됨 — 비우기 권함.

---

## 원문(사용자가 가져온 일반 점검표, 2026-11-20)

## 1. 현재 상황

- 네이버 로그인 화면 정상 표시
- 계정 로그인 및 사용자 정보 제공 동의 화면까지 정상 표시
- 동의 후 `nid.naver.com`의 일반 접속 오류 화면 표시
- 네이버 개발자센터의 Callback URL과 인증 요청의 `redirect_uri`가 동일하다고 확인함
- Callback URL 형식: `http://192.168.x.x:포트/...` (사설 LAN IP)
- 바로픽 구조: 고객앱 → API Gateway → auth-service, 네이버 OAuth2 연동

**현재 화면만으로 원인을 확정할 수 없다.** 특히 오류가 네이버 도메인에서 표시되었다는 사실만으로 Gateway나 auth-service에 요청이 도달했는지는 알 수 없다.

## 2. 우선순위별 원인 후보

| 우선순위 | 후보 | 확인 방법 |
|---|---|---|
| 1 | 네이버에서 HTTP + 사설 LAN IP Callback을 허용하지 않거나 특정 환경에서 거부 | 네이버 개발자센터 등록 정책 및 인증 단계의 실제 응답 확인 |
| 2 | 실제 OAuth2 요청의 `redirect_uri` 인코딩·파라미터 오류 | 브라우저 개발자 도구에서 `/oauth2.0/authorize` 요청 URL 및 응답 확인 |
| 3 | `state` 생성·전달·검증 문제 | 요청의 state와 Callback에 반환된 state 비교, 세션/쿠키 확인 |
| 4 | 휴대폰에서 LAN IP:포트로 접근 불가 | 동일 Wi-Fi에서 휴대폰 브라우저로 Gateway의 공개 테스트 경로 접속 |
| 5 | API Gateway 라우팅 또는 프록시 헤더 설정 오류 | Callback 도달 로그, Host/X-Forwarded-* 및 Spring Security 설정 확인 |
| 6 | 네이버 일시 장애·브라우저 환경 문제 | Chrome에서 재시도, 다른 계정/네트워크에서 재현 여부 확인 |

사설 IP가 반드시 금지된다고 단정하지 않는다. 네이버의 현재 정책과 실제 요청 결과를 확인해야 한다.

## 3. 정상 인증 흐름

```text
고객앱
  → API Gateway (LAN IP:포트)
  → auth-service (네이버 로그인 시작)
  → 네이버 인증 서버 (로그인 및 동의)
  → 브라우저가 redirect_uri로 이동
  → API Gateway의 Callback 경로
  → auth-service가 code/state 처리
  → auth-service가 네이버와 서버 간 토큰 교환
  → 바로픽 로그인 완료
```

네이버가 서버에서 직접 LAN IP로 접속하는 것이 아니라, 일반적인 Authorization Code 흐름에서는 **사용자 브라우저가 Callback URL로 이동**한다. 따라서 휴대폰이 해당 LAN IP에 도달할 수 있는지도 중요하다.

## 4. 정확한 점검 절차

### 4.1 네이버 로그인 요청 URL 확인

예시:

```text
https://nid.naver.com/oauth2.0/authorize
  ?response_type=code
  &client_id=CLIENT_ID
  &redirect_uri=http%3A%2F%2F192.168.0.32%3A8080%2Flogin%2Foauth2%2Fcode%2Fnaver
  &state=RANDOM_VALUE
```

- 실제 요청에서 `redirect_uri`를 URL 디코딩하여 개발자센터 등록값과 비교한다.
- `http`/`https`, IP, 포트, 경로, 끝의 `/`까지 확인한다.
- `state`가 비어 있지 않은지 확인한다.
- 인증 요청의 `client_id`가 등록한 네이버 앱과 일치하는지 확인한다.
- URL 전체를 외부에 공유할 때는 민감한 파라미터를 마스킹한다.

### 4.2 동의 버튼 직후 브라우저 이동 확인

브라우저 개발자 도구의 Network 탭에서 **Preserve log**를 켜고 재현한다.

| 관찰 결과 | 의미 | 다음 조치 |
|---|---|---|
| `nid.naver.com` 오류만 발생하고 Callback 요청 없음 | 네이버 인증 단계에서 중단 가능성 | 네이버 응답, 앱 설정, 파라미터 및 정책 확인 |
| Callback 요청이 시도되지만 연결 실패 | 휴대폰의 LAN 접근 문제 가능성 | Wi-Fi, IP, 포트, 방화벽, 서버 바인딩 확인 |
| Callback 요청이 Gateway에 도착했으나 404/401/403 | 라우팅 또는 보안 설정 문제 가능성 | Gateway 라우트 및 Callback 공개 경로 확인 |
| Callback에 `code`가 도착한 후 실패 | 토큰 교환 또는 로그인 처리 문제 가능성 | auth-service 로그 및 네이버 토큰 응답 확인 |
| Callback에 `error`가 도착 | 네이버에서 OAuth 오류 반환 | 반환된 오류 코드/설명 확인 |

### 4.3 휴대폰의 LAN 접근 확인

- 휴대폰과 서버가 동일한 네트워크에 있는지 확인한다.
- 휴대폰 브라우저에서 `http://192.168.x.x:포트/공개-테스트-경로`로 접근한다.
- 서버가 `127.0.0.1`에만 바인딩되어 있지 않은지 확인한다.
- 방화벽에서 해당 포트의 LAN 접근을 허용하는지 확인한다.
- `localhost`는 휴대폰 자신을 의미하므로 PC 서버의 주소로 사용할 수 없다.

### 4.4 바로픽 API Gateway / auth-service 확인

```text
외부 Callback: http://192.168.x.x:8080/login/oauth2/code/naver
                      ↓
                  API Gateway
                      ↓
                 auth-service
```

- 네이버에 등록하는 Callback은 외부 브라우저가 접근할 수 있는 **Gateway 주소**여야 한다.
- Gateway가 해당 Callback 경로를 auth-service로 라우팅하는지 확인한다.
- OAuth2 Callback 경로가 로그인 전 사용자에게도 접근 가능하도록 설정되었는지 확인한다.
- Spring Security가 프록시 헤더로부터 잘못된 내부 주소를 생성하지 않는지 확인한다.
- 설정 예시 `redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"`를 사용하는 경우 실제 확장 결과를 확인한다.
- 예시 경로는 프로젝트의 실제 엔드포인트와 다를 수 있다.

## 5. 권장 해결 순서

1. 네이버 동의 직후 **실제 Callback 요청이 발생하는지** 확인한다.
2. 발생하지 않는다면 네이버 개발자센터의 앱 설정·Callback 허용 정책·OAuth2 요청 파라미터를 검증한다.
3. Callback 연결에 실패한다면 휴대폰에서 LAN Gateway 접근을 테스트한다.
4. Gateway에 도달한다면 Gateway와 auth-service의 라우트 및 Spring Security 로그를 확인한다.
5. LAN HTTP Callback이 정책상 허용되지 않는 것이 확인되면, 개발용 HTTPS 터널 또는 개발 도메인을 고려한다. 터널 사용 시 등록 Callback과 실제 `redirect_uri`를 동일한 외부 HTTPS 주소로 맞춘다.

## 6. 진단에 필요한 정보

다음 정보가 있으면 원인을 더 정확히 좁힐 수 있다.

- 네이버 개발자센터에 등록한 Callback URL (민감 정보 제외)
- 실제 `/oauth2.0/authorize` 요청의 `redirect_uri` 값
- 로그인 환경: Flutter/Android 앱 내부 브라우저, 시스템 브라우저, React 웹 중 어느 것인지
- 동의 직후 Network 탭에서 마지막 요청 URL 및 HTTP 상태 코드
- 해당 시각 Gateway와 auth-service의 Callback 관련 로그

**보안:** Client Secret, Authorization Code, Access/Refresh Token, 전체 세션 쿠키는 공유하거나 로그에 출력하지 않는다.
