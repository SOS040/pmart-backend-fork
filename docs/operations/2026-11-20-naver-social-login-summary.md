# 네이버 소셜 로그인 적용 — 문제와 해결 정리 (2026-11-19 ~ 11-20)

- 작성: PC 1, 2026-11-20 (사용자 "여태 네이버 소셜로그인 적용과 관련된 에러사항 md 로 만들어줘")
- 갱신: 2026-11-21 — 구글·카카오·네이버 공통으로 바뀐 것(계정 고르기·동의는 처음 가입 때만·탈퇴 때 연결 끊기·네이버 이메일, 고객앱 회신 반영). 아래 "2026-11-21 추가" 절
- 대상: 고객 앱(customer_app)·라이더 앱(rider_app) 네이버 로그인, 백엔드 auth-service·rider-auth-service
- 관련 결정: ADR-0055·ADR-0071(소셜 로그인 = ID 토큰 검증), **ADR-0078**(네이버만 서버가 인가 코드 교환), ADR-0074(가입 휴대폰 확인)
- 관련 문서: `docs/operations/2026-11-19-naver-oauth-lan-callback-diagnosis.md`(콜백 진단), `docs/handoff/clients/customer-app/` 의 2026-11-20 요청·회신들

## 한눈에 — 로그인 흐름 (지금, 2026-11-20 저녁)
```
앱(AppAuth, PKCE S256) → 네이버 로그인·정보 제공 동의(nid.naver.com, Chrome 탭)
  → 네이버가 콜백 중계 주소로 보냄: http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/{customer|rider}
  → auth-service 가 "PMart 앱으로 돌아가기" 페이지(200 HTML) → 앱 주소 com.pmart.customer://oauth/naver?code=…
  → 앱이 인가 코드를 서버로: POST /auth/naver-token-exchanges (라이더: /rider-auth/naver-token-exchanges)
  → 서버가 Client Secret 으로 네이버 토큰 교환 → id_token(+ 고객은 프로필 사진 주소) 만 돌려줌
  → 앱 → POST /auth/social-logins {provider: NAVER, providerToken: id_token, profileImageUrl}
  → 약관 동의(AUTH_CONSENT) → 휴대폰 확인(AUTH_IDENTITY) → 회원 토큰
```

- 2026-11-21 바뀐 점: 네이버 인가 요청에 `auth_type=reauthenticate`(로그인마다 아이디·비밀번호). 서버는 교환 때 네이버 **연결을 끊지 않는다**(동의는 처음 가입 때만 — 아래). 교환 때 이메일도 받아 둔다(BR-77).

## 겪은 문제와 해결 (순서대로)

| # | 증상 | 원인 | 해결 | 상태 |
|---|---|---|---|---|
| 1 | 네이버 콘솔에 앱 주소(`com.pmart.customer://…`)를 Callback URL 로 넣을 수 없음 | 네이버는 http(s) 주소만 받음, 우리는 도메인 없음 | **콜백 중계**: LAN 게이트웨이 주소를 Callback URL 로, auth-service 가 받은 쿼리를 앱 주소로 넘김(BR-66, auth 0.5.0) | 배포 11-19 |
| 2 | 11-19 저녁 네이버 로그인 422 | 서버 설정에 네이버 Client ID(ID 토큰 aud) 없음 | pmart-config 0752d9f 에 client-ids | 해결 11-19 22:20 |
| 3 | 동의 뒤 `nid.naver.com` "동시 접속자가 많거나 네트워크가 불안정…" 화면, 앱으로 안 돌아옴 | 네이버가 스크립트로 콜백에 보낸 뒤 이어진 **302 → 앱 주소**를 Chrome 이 "사용자 동작 없음"으로 막음 | auth 0.7.0: 302 대신 **"앱으로 돌아가기" 페이지**(자동 시도 + 버튼) — 1d3df48a | 해결 11-20 오전 |
| 4 | 앱으로는 돌아오는데 로그인이 끝나지 않음, 다시 네이버 오류 화면 | AppAuth `No stored state` — MainActivity `android:taskAffinity=""` 로 로그인 시작 화면과 응답 받는 화면이 **다른 task** 로 갈려 저장 상태를 못 찾음 | pmart-clients dbee5438: 고객·라이더·MFC 앱 MainActivity 의 `taskAffinity=""` 삭제 | 코드 11-20 |
| 4-1 | 고친 뒤에도 같은 증상 | **폰에 설치된 APK 가 고치기 전 빌드**(PC 1 `pmart-clients` 작업 폴더가 origin 보다 뒤처져 있었음 — APK 매니페스트를 `aapt2 dump` 로 확인) | 최신 main 으로 다시 빌드·설치 | 해결(재설치 뒤) |
| 5 | "정보 제공 동의 화면이 두 번" 나옴 | 4 때문에 첫 시도가 실패 → 고객이 다시 시도해 두 번 본 것(네이버·서버 문제 아님) | 4 해결로 사라짐 | — |
| 6 | 앱으로 돌아온 직후 "로그인 서비스가 원활하지 않아요" (BR-69) | 네이버 토큰 엔드포인트가 **client secret 없는 교환을 거절**(`invalid_client`) — OIDC 설정 `token_endpoint_auth_methods_supported` = `client_secret_post` 만. ADR-0071 의 "앱이 secret 없이 PKCE 로 교환" 전제가 네이버에서는 틀림 | **ADR-0078**: 교환 전용 API(auth 0.10.0·rider-auth 0.3.0). Secret 은 서버 `{cipher}`(원본 `deploy/lan/.secrets/naver-{customer,rider}-client-secret.txt` — 앱 `.secrets/*.env` 에서 옮김) | 배포 11-20 15:29 |
| 7 | 휴대폰 확인 "보냈어요"가 400 | auth → customer 내부 확인 호출에 `Idempotency-Key` 빠짐(시험 WireMock 이 헤더를 안 봄) | d75fe0b9 — 헤더 추가, IT 에 헤더 검증 | 배포 11-20 11:54 |
| 8 | 가입 도중 나갔다 다시 로그인하면 동의 화면 없이 넘어감 (BR-72) | 저장된 동의가 충족이면 건너뜀 | 회원이 된 적 없는 계정은 늘 동의부터(fresh, 기기 동의도 무시) — bc730a63 | 배포 11-20 18:32 |
| 9 | "입력하신 아이디로 로그인할 수 없습니다 — 현재 PMart은 **개발 중 상태**… 등록된 아이디만" | 네이버 개발자센터 앱 상태가 **개발 중** → 관리자·**테스터로 등록한 네이버 ID** 만 로그인 가능. 디버그 때는 등록된 계정이었고 이번엔 다른 계정. profile/debug 빌드 차이 아님 | 개발자센터 → 내 애플리케이션 → PMart → **멤버관리 → 테스터 ID 등록**(사용자 몫) | 사용자 조치 |

## 프로필 사진 — ID 토큰에는 없고 access token 으로만
- 네이버 **ID 토큰(id_token)에는 프로필 사진이 없다.** (구글은 ID 토큰 `picture`, 카카오도 ID 토큰 `picture` 가 있어 서버가 바로 쓴다.)
- 네이버 사진은 **access token 으로 프로필 API** `GET https://openapi.naver.com/v1/nid/me` 를 불러야 `response.profile_image` 로 받는다.
- 흐름 변화:
  1. auth 0.8.0(11-20 오전): 앱이 토큰 교환 결과의 access token 으로 프로필 API 를 불러 `profileImageUrl` 을 로그인 본문에 실어 보냄(서버는 https·`*.pstatic.net` 만 받음).
  2. ADR-0078(서버 교환) 뒤에는 앱에 access token 이 없어 1 을 할 수 없게 됨(BR-70).
  3. **auth 0.10.1**(11-20 16:01): 서버가 교환 때 받은 access token 으로 프로필 API 를 **한 번** 불러 교환 응답에 `profileImageUrl` 을 실어 줌. access/refresh token 은 저장·반환하지 않고 버림. 앱은 받은 주소를 로그인 본문에 그대로.
- customer-service 는 허용 호스트(`pstatic.net`)의 사진을 받아 사본을 둔다.
- 네이버 콘솔 **제공 정보에서 "프로필 사진" 체크가 필요**(사용자 몫). 없으면 `profileImageUrl` 은 null.
- 라이더 앱은 프로필 사진 기능이 없어 해당 없음.

## 네이버 앱 상태 — "개발 중"과 "검수(서비스 적용)"
| 상태 | 누가 로그인 | 필요한 것 |
|---|---|---|
| **개발 중**(지금) | 앱 관리자 + 테스터로 등록한 네이버 ID | 없음 — 멤버관리에서 테스터 ID 등록 |
| **서비스 적용** | 모든 네이버 사용자 | 네이버에 검수 요청(서비스 소개·개인정보 처리방침 주소·화면 캡처 등 제출 → 네이버 확인) |
- 개발·시연 단계는 "개발 중 + 테스터 등록"으로 충분하다. 검수는 정식 공개 때만. 지금은 콜백이 LAN 사설 주소라 검수 대상으로도 맞지 않는다.

## 2026-11-21 추가 — 세 제공자 공통 동작 (사용자 결정 "처음 가입할 때만"·"1, 2 둘다")

정한 것: **로그인 버튼을 누를 때마다 계정을 고르거나 다시 로그인한다. 정보 제공 동의 화면은 처음 가입할 때만 나온다. 탈퇴하면 그 제공자와 앱 연결을 끊어서, 다시 가입할 때 동의 화면이 다시 나온다.**

| | 구글 | 카카오 | 네이버 |
|---|---|---|---|
| 로그인 버튼마다(앱) | `GoogleSignIn.authenticate()` — 늘 계정 선택 창, 자동 선택 없음. 로그아웃 때 `signOut()` | 카카오톡 앱 자동 로그인 대신 늘 `loginWithKakaoAccount(prompts: [Prompt.login])`. 로그아웃·탈퇴 때 SDK `logout()` | 인가 요청 `auth_type=reauthenticate`(`reprompt` 안 씀) |
| 동의 화면 | 처음 가입 때만(앱이 `consent` 류 값을 보내지 않음) | 같음 | 같음 — 11-20 에 켰던 "교환마다 연결 끊기"(BR-74, `naver.unlink-after-exchange`)를 **11-21 끔**(pmart-config 80bbe9d) |
| 탈퇴 때 연결 끊기 | **앱**이 탈퇴 성공 응답 뒤 `GoogleSignIn.disconnect()` → `signOut()`. 실패·취소·결과 불명이면 안 끊음, 로그인·로그아웃 때도 안 끊음 | **서버**(auth-service)가 Admin 키로 카카오 앱 연결 끊기(289a989a, 키 pmart-config 4c25f19 `{cipher}`, 원본 `deploy/lan/.secrets/kakao-admin-key.txt`) | **서버**가 탈퇴 재인증 교환 때 끊음(BR-74 의 탈퇴 경로는 그대로) |
| 이메일 | ID 토큰 `email` | ID 토큰 `email`(카카오 비즈 앱 전환·이메일 동의항목 켬 — 사용자 11-21) | ID 토큰에 없음 → **BR-77**(2b3eca38, auth V7 `naver_profile_email`): 교환 때 `GET /v1/nid/me` 의 email 을 그 id_token `sub` 에 10분 묶어 두었다가(원 sub 대신 SHA-256) 로그인 때 싣는다. 앱이 보낸 값은 받지 않음. 동의 안 함·실패·10분 지남이면 이메일 없이 로그인 성공. 네이버 콘솔 "이메일 주소" 켬(사용자) |

- 세션이 끝났을 때 구글은 계정 선택 없이 조용히 다시 로그인을 먼저 시도한다(`attemptLightweightAuthentication`) — 로그인 버튼이 아니라 그대로 둔다.
- LAN 배포: auth-service 2b3eca38(BR-77)·289a989a(카카오 연결 끊기) PC 4 11-21 16:05, 설정 80bbe9d·4c25f19 반영.
- 고객앱 반영: pmart-clients 2e3ec6da(11-21 18:11) — 회신 `pmart-clients/docs/handoff/customer-app/2026-11-21-backend-social-login-chooser-and-revoke-reply.md`. 서버 변경 요청 없음. 백엔드 요청서 `docs/handoff/clients/customer-app/2026-11-21-request-google-revoke-on-withdrawal.md`·`…-request-login-account-chooser.md`.
- 라이더 앱(rider-auth-service, PC 5)은 아직 이 방식으로 맞추지 않았다 — 맞추려면 라이더용 카카오 Admin 키(`deploy/lan/.secrets/kakao-rider-admin-key.txt`)가 필요할 수 있다(사용자 답 기다림).

## 남은 일
- [ ] 시험에 쓰는 네이버 ID 를 고객 앱(`GKQj…`)·라이더 앱(`Q5oG…`) 테스터로 등록 (사용자)
- [ ] 네이버 콘솔 제공 정보 "프로필 사진" 체크 (사용자)
- [ ] 네이버 콘솔 "연결 끊기 Callback URL" 이 로그인 콜백과 같음 → 비우기 권장(네이버가 사설 IP 에 못 닿고 받는 기능 없음)
- [ ] 앱 빌드 설정의 `NAVER_CLIENT_SECRET` 삭제(고객 `apps/customer_app/.secrets/customer.env`, 라이더 PC 5 `.secrets/rider.env`) — 서버로 옮김. 필요하면 콘솔에서 Secret 재발급 후 서버 값만 바꾸기
- [ ] 고객·라이더 앱: 코드 교환 API(BR-69 회신) 반영 빌드로 실기기 확인
- [ ] (11-21) 새 고객앱 APK 로 실기기 확인(사용자): ① 로그아웃 → 로그인 버튼 — 제공자마다 계정 선택·로그인 화면, 같은 계정이면 동의 없이 들어감 ② 구글·카카오·네이버 각각 가입 → 탈퇴 → 다시 가입 때 동의 화면이 다시 뜸 ③ 네이버·카카오 로그인 뒤 내 정보 이메일. 확인 뒤 customer_service DB 에서 provider 별 `count(email)` 숫자만 본다(원문 보지 않음)
- [ ] (11-21) 라이더 앱도 "동의는 처음 가입 때만"으로 맞출지 — 사용자 답 기다림
