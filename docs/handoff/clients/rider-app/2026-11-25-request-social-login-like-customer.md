보낸 영역: platform(PC 1)
받는 앱: rider-app
작성: pmart-backend, 2026-11-25
상태: 1·2 **LAN 배포 끝**(2026-11-25 0c51442f) · 3 **구현 — LAN 배포 전**

# 요청: 라이더 소셜 로그인을 고객 앱과 같게 — 탈퇴 때 구글 연결을 서버가 끊기

사용자 2026-11-25 "라이더쪽에도 지금 고객앱 소셜로그인 기능 구조처럼 만들어줘". 고객 앱(customer 0.18.0 BR-105·auth 가입 전 끊기 없앰)과 같은 두 가지를 라이더 서버에 넣었다.

## 1. 앱 변경 부탁 — 탈퇴 요청에 구글 접근 토큰 싣기
- `POST /riders/me/withdrawals` 본문에 선택 `providerAccessToken`(문자열, 4096자 이하) — **구글로 로그인한 라이더만**. 탈퇴 직전 `google_sign_in` 으로 받은 구글 OAuth **접근 토큰**(ID 토큰 아님). 카카오·네이버는 보내지 않는다(지금처럼 서버가 끊는다).
  ```json
  { "reason": "그만둠", "providerAccessToken": "ya29.…" }
  ```
- 탈퇴가 **완료(202 APPLIED)** 되면 rider-service 가 `https://oauth2.googleapis.com/revoke` 로 이 앱과의 구글 연결을 끊는다 — 다시 가입할 때 구글 정보 공유 동의 화면이 뜬다. 막힘(409 INVALID_STATE)이면 끊지 않는다.
- 토큰은 저장·로그·응답하지 않고 한 번 쓰고 버린다. 끊기가 실패해도 탈퇴는 그대로.
- 앱의 기기 `disconnect()`(revokeAccess)는 그대로 둔다 — 두 겹. 토큰을 못 얻으면 빼고 보내면 된다(지금과 같음).
- 접근 토큰 얻기: `google_sign_in` 7 의 `authorizationClient.authorizationForScopes([...])`(또는 탈퇴 재인증 때 받은 인증) 의 `accessToken` — 고객 앱 BR-105 와 같은 방법.

## 2. 앱 변경 없음 — 네이버 동의 화면이 또 뜨던 것
- 서버가 네이버 로그인 교환 때 "아직 가입을 마친 라이더가 없으면" 연결을 끊던 것(RA-1 ③, `purpose=SIGNUP_PENDING`)을 없앴다. 교환 때는 곧 가입을 마칠 사람도 "가입 전"이라, 가입 뒤 첫 로그인·탈퇴 때 네이버 동의 화면이 한 번 더 떴다.
- 이제 네이버·카카오 동의 화면은 **처음 가입·탈퇴 뒤 다시 가입** 때만. 연결 끊기는 탈퇴 재인증(`purpose=WITHDRAWAL`)·탈퇴 반영(카카오) 때만.

## 확인 부탁
- 구글 탈퇴 뒤 다시 구글 로그인했을 때 동의 화면이 뜨는지, 네이버 가입 뒤 로그아웃 → 다시 로그인에 동의 화면이 안 뜨는지(LAN 배포 뒤).
- 서버 쪽 로그는 `구글 연결 끊기 ok=true|false reason=http_<코드>`(rider-service), `네이버 연결 끊기 purpose=WITHDRAWAL ok=…`(rider-auth) 만 남는다.

## 3. 소셜 이름·닉네임(2026-11-25 추가, rider-service 0.30.0 — 사용자 "이름 닉네임 먼저해줘")
- 새 `GET /riders/me/provider-profile`(라이더 토큰, **가입 전에도**) → `{provider, name, providerNickname, authenticatedAt}`. 사본이 아직 없으면 모든 칸이 빈 200.
  - `name` = 제공자 실명(구글 이름·네이버 이름). **카카오는 실명을 주지 않아 없음**.
  - `providerNickname` = 카카오 닉네임·네이버 별명, 없으면 이름(구글은 이름).
- 가입 화면 이름 칸은 `name` 으로 미리 채워 주세요(라이더가 고쳐서 가입하면 그 값이 라이더 실명 `name` — 서버는 소셜 이름으로 바꾸지 않는다). 카카오면 비워 두고 입력받기.
- `GET /riders/me` 에 `providerNickname`(표시용). 실명 `name` 은 그대로 가입 입력.
- 로그인마다 최신 값. 배포 전에 로그인한 라이더는 다음 로그인부터 채워진다. 탈퇴하면 지운다.
- 카카오 닉네임은 카카오 콘솔 동의 항목 "닉네임", 네이버 이름·별명은 네이버 콘솔 제공 정보를 켜야 온다(고객 앱과 같은 앱 키면 이미 켬).
- **사진(2026-11-25 추가, rider-service 0.31.0 — 사용자 "프로필 사진도 해줘")**: `provider-profile`·`/riders/me` 의 `profileImageUrl`(= `/api/v1/riders/me/profile-image?v=…`, 사본 없으면 null) → `GET /riders/me/profile-image` 를 **본인 토큰 헤더로** 받아 그린다(`<img src>` 만으로는 안 됨, 고객 앱 /me/profile-image 와 같음). v 가 바뀌면 다시 받기. 가입 전에도. 사본은 로그인 뒤 서버가 비동기로 만들어 1~2초 늦을 수 있다(null 이면 기본 아바타). 카카오 기본 사진은 사본 없음.
