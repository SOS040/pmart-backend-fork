# 회신 — BR-74 네이버 로그인마다 정보 제공 동의 다시

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-20 — 사용자 "(A)+(C)"
- 답하는 것: pmart-clients `docs/handoff/customer-app/2026-11-20-backend-naver-consent-every-login.md` R1~R5
- 상태: 아래 "배포 상태" 줄

## 정한 방식
- **(A) 로그인마다 끊기 = LAN 시험용 스위치**(`pmart.auth.customer.naver.unlink-after-exchange`, 기본 꺼짐). 켜면 `POST /auth/naver-token-exchanges`(purpose LOGIN) 뒤 서버가 네이버 앱 연결을 끊어 다음 로그인에서 동의 화면이 다시 나온다. **운영 동작으로 매번 끊지는 않는다**(사용자 결정 — 실제 고객은 한 번 동의하면 다시 묻지 않음).
- **(C) 탈퇴 때 끊기**: 교환 본문에 `purpose: "WITHDRAWAL"`(auth 0.11.0) 을 보내면 서버가 교환·프로필 뒤 연결을 끊는다. 탈퇴 재인증(`POST /me/withdrawals`·철회의 `providerToken`)을 위한 네이버 교환에는 이 값을 넣어 주세요. 로그인 교환은 생략(LOGIN 기본).

## R1~R5
- **R1**: 위 (A) — 스위치가 켜졌을 때만. 끊기는 프로필 사진(BR-70)을 읽은 **뒤**. 실패해도 교환은 200(다음 로그인에 동의 화면이 안 나올 수 있음).
- **R2**: 네이버 사용자 ID(sub)는 앱마다 고정으로 알고 있어 끊었다 다시 이어도 같은 PMart 계정이어야 한다 — **확인 전이라 스위치는 꺼 둠.** 확인 방법: 네이버 "내정보 → 연결된 서비스 관리"에서 PMart 를 끊고 같은 네이버 계정으로 다시 로그인 → 새 가입(약관·휴대폰 확인)이 아니라 기존 계정으로 들어오면 sub 유지. 확인되면 PC 1 이 LAN 스위치를 켠다.
- **R3**: 스위치 방식(가입 중 여부와 상관없이 켜면 매번). 운영은 끔.
- **R4**: 탈퇴는 `purpose: WITHDRAWAL` 로 끊음(C). 철회되면 다음 로그인에서 동의만 다시 받고 같은 계정(sub 유지 전제).
- **R5**: 끊기에 쓴 access token·secret 은 저장·로그하지 않는다(결과 코드만 로그).

## 앱
- 아이디·비밀번호를 매번 받는 `auth_type=reauthenticate` 도 시험용으로만(운영 빌드에서는 빼는 걸 권장).
- 탈퇴·철회 재인증의 네이버 교환에 `purpose: "WITHDRAWAL"`.

## 배포 상태
- (작성 시점) 서버 구현·시험 끝(auth check 통과), LAN 배포 전.
