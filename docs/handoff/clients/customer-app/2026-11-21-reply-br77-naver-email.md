# 회신 — BR-77 네이버 로그인 계정 이메일

보낸 영역: platform
받는 앱: customer-app
답하는 것: `pmart-clients/docs/handoff/customer-app/2026-11-21-backend-naver-email.md`
작성: pmart-backend, 2026-11-21 — 사용자 "BR-77 지금 해줘"

## 정한 것
- **R1** 서버가 네이버에서 직접 받은 값만 쓴다. `POST /auth/naver-token-exchanges` 때 이미 부르는 `GET /v1/nid/me` 응답의 `response.email` 을 그 교환의 id_token `sub` 에 **10분** 묶어 두고(서버 DB, 원래 sub 대신 해시), 같은 sub 의 검증된 id_token 으로 로그인이 끝날 때(회원 토큰을 줄 때 — 동의·휴대폰 확인 단계를 거치면 그 완료 때) `CustomerAuthenticated.email` 로 customer-service 에 넘기고 지운다. 교환 응답에는 이메일을 싣지 않는다 — **앱 변경 없음, 계약 변경 없음**(auth 0.11.0 그대로).
- `emailVerified`: 네이버는 확인 여부를 주지 않아 **싣지 않는다**(customer 프로필은 기존 값 유지, 새 계정은 null).
- **R2** 이메일 동의 안 함·프로필 API 실패·시간 초과·10분 지남이면 이메일 없이 로그인은 그대로 성공. customer-service 는 사건에 없는 값은 기존 값을 그대로 둔다(빈 값으로 지우지 않음).
- **R3** 이미 가입한 네이버 계정은 다음 네이버 로그인(교환 → 로그인) 때 채워진다. 따로 옮길 것 없음.
- **R4** access token·이메일 원문은 로그·응답에 남기지 않는다. 보관 행은 로그인 때 지우고, 남은 것은 만료 뒤 주기 작업이 지운다.

## 사용자 작업
- 네이버 개발자센터 → 내 애플리케이션 → API 설정 → 제공 정보 선택에서 **"이메일 주소"** 를 켠다. 켜기 전에 동의한 계정은 네이버가 다시 동의를 묻거나 값을 주지 않을 수 있다(그때는 이메일 없이 로그인).

## 상태
- 서버 시험 통과(auth-service check). LAN 배포는 PR 세션 경로로 — 배포되면 실기기 네이버 로그인 → 내 정보 이메일 확인 부탁.
