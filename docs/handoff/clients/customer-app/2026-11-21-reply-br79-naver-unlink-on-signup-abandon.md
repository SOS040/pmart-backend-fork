# 회신 — BR-79 네이버 동의 뒤 가입을 그만두면 네이버 연결 끊기

보낸 영역: platform
받는 앱: customer-app
답하는 것: `pmart-clients/docs/handoff/customer-app/2026-11-21-backend-naver-unlink-on-signup-abandon.md`
작성: pmart-backend, 2026-11-21 — 사용자 "(가)로 진행"·"BR-79 진행해줘"

## 정한 것 — (가) 교환 때 판단, 앱 변경 없음
- **R1** `POST /auth/naver-token-exchanges`(purpose LOGIN)에서 네이버가 준 id_token 의 `sub` 로 **가입을 마친 PMart 계정이 없으면** — 처음 오는 네이버 계정이거나, 전에 약관 동의·휴대폰 확인에서 나가 회원이 된 적 없는 계정 — 프로필(사진·이메일)을 읽은 **뒤** 네이버 앱 연결을 끊는다(auth 계약 0.11.1).
  - 그래서 가입을 끝내지 않고 나가면 다음 네이버 로그인에서 동의 화면이 다시 나온다.
  - 가입을 마친 회원은 끊지 않는다. 처음 가입한 사람은 가입 직후 **다음 한 번**만 동의를 다시 보고(가입 교환 때 끊겼으므로), 그 뒤로는 묻지 않는다 — 요청서 (가) 설명과 같다.
  - 요청·응답 모양은 그대로다. id_token·프로필 사진·이메일(BR-77)은 전과 같이 나간다.
- **R2** LAN `naver.unlink-after-exchange` 는 **꺼져 있다**(false, pmart-config 80bbe9d). 그대로 둔다.
- **R3** 끊기 실패·계정 조회 실패는 교환을 막지 않는다(조회 실패면 끊지 않음). id_token 에서 sub 를 읽을 수 없어도 끊지 않는다. 토큰·sub 는 로그에 남기지 않는다(로그는 `purpose=SIGNUP_PENDING ok=true|false` 만).
- 탈퇴 재인증(purpose WITHDRAWAL)은 전처럼 늘 끊는다.

## 주의 — 실기기로 꼭 볼 것
- 네이버 사용자 ID(sub)는 앱마다 고정이라 끊었다 다시 이어도 같다고 보고 만들었다(BR-74 R2 와 같은 전제). **새 네이버 계정으로 가입을 끝낸 뒤 다시 네이버 로그인 → 동의 화면 한 번 → 새 가입 화면(약관·휴대폰)이 아니라 같은 계정으로 들어오는지** 확인해 달라. 새 가입으로 가면 sub 가 바뀐 것이니 바로 알려 달라(서버에서 끄겠다).

## 확인 순서(실기기)
1. 새 네이버 계정 로그인 → 동의 → 약관 화면에서 나감 → 다시 네이버 로그인: 동의 화면이 다시 뜬다.
2. 이번엔 가입 끝까지(약관·휴대폰) → 로그아웃 → 네이버 로그인: 동의 화면 한 번 → **같은 계정**으로 들어간다.
3. 다시 로그아웃 → 네이버 로그인: 동의 화면 없이 들어간다.

## 상태
- 서버 시험 통과(auth-service check — 단위·통합, 새 시험 `NaverCodeExchangeIT.unlinksNaverWhenSignupNotFinishedButNotForMembers`).
- **LAN 배포 2026-11-21 20:5x KST** — PC 4 auth-service(이미지 7b5bb37f, digest sha256:39e88bd4…), 시작 정상·ERROR 0. 실기기 확인 부탁(위 순서).
