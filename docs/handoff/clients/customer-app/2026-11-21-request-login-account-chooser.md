# 요청 — 소셜 로그인 때마다 계정 고르기·다시 로그인(다른 계정으로 바꿀 수 있게)

보낸 영역: platform
받는 앱: customer-app
작성: pmart-backend, 2026-11-21 — 사용자 "한 번 권한 선택하면 그 이후로는 그 단계가 스킵돼서 다른 계정으로 선택해서 로그인도 불가능", 추천안 "매번 계정 고르기" 수락("그렇게 요청서 보내줘")

## 정한 규칙
- **동의(정보 제공) 화면은 처음 가입할 때만** — 그대로(서버: 카카오 탈퇴 뒤 연결 끊기, 네이버 로그인마다 끊기 끔, 구글은 `2026-11-21-request-google-revoke-on-withdrawal.md`).
- **계정 고르기·로그인 화면은 로그인 버튼을 누를 때마다** — 이 요청. 서버 변경 없음(ID 토큰·교환 API 그대로).

## 요청
- **R1. 구글**: 로그인 버튼마다 계정 선택 화면이 뜨게. 앱 로그아웃 때 Google Sign-In `signOut()`(Credential Manager 면 `clearCredentialState`)도 부르고, 로그인 요청은 자동 선택을 끈다(예: `setAutoSelectEnabled(false)`, 저장된 계정만 거르는 필터 끔). `revokeAccess` 는 로그아웃이 아니라 탈퇴 때만(앞 요청서).
- **R2. 카카오**: 카카오톡 앱 자동 로그인 대신 카카오계정 로그인 화면으로, 매번 다시 로그인하게 — Kakao SDK `loginWithKakaoAccount(prompts: [Prompt.login])`(OIDC `prompt=login`). 앱 로그아웃 때 Kakao SDK `logout()` 도 부른다(서버 탈퇴 끊기와 별개).
- **R3. 네이버**: 인가 요청에 `auth_type=reauthenticate` 를 붙여 매번 네이버 로그인 화면이 뜨게(PKCE·콜백·서버 교환 `POST /auth/naver-token-exchanges` 는 그대로). 정보 제공 동의를 다시 묻는 `auth_type=reprompt` 는 쓰지 않는다.
- **R4.** 위 어느 것도 동의 화면을 매번 띄우지 않는다(처음 가입 때만). 실패·취소는 지금 로그인 취소 처리 그대로.

## 확인 방법
- 로그인 → 로그아웃 → 로그인 버튼: 제공자 계정 선택·로그인 화면이 다시 뜨고 다른 계정으로 들어갈 수 있다. 같은 계정이면 동의 화면 없이 바로 들어간다.
- 새 계정으로 처음 가입할 때만 동의 화면이 뜬다.
