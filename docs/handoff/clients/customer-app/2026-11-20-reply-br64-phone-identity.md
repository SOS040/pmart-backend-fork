# 회신: BR-64 가입·로그인 휴대폰 확인 (R1~R7)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-20
- 받는 문서(pmart-clients):
  - `docs/handoff/customer-app/2026-11-19-backend-social-login-identity.md`(R1~R7)
  - 같은 폴더 `2026-11-19-backend-phone-verification-signup.md`
- 결정 기록: `docs/adr/0074-customer-signup-phone-identity.md`
- 사용자 결정:
  - 11-19: 가입하자마자 확인, 한 번호 한 계정, 안내에는 제공자·가입일·가린 이메일 전부, 탈퇴 1달 재가입 금지
  - 11-20: 기존 회원은 "(가) 다음 로그인 때 막고 확인"
- 서버 상태: auth 0.6.0·customer 0.10.0 구현·시험 끝. **LAN 배포는 사용자 허락 뒤**(아래 '상태').
  - 스위치 `require-identity`는 **꺼 둔 채로** 배포한다. 앱이 반영한 뒤 켠다.

## 흐름 (로그인 → 필수 동의 → 휴대폰 확인 → 회원)
1. `POST /auth/social-logins`
   - 필수 동의가 비면 지금처럼 `AUTH_CONSENT`가 온다. `/completions`로 동의를 마치면 다음 단계로 간다.
   - 확인이 필요하면 **`AUTH_IDENTITY`**(nextAction `VERIFY_IDENTITY`)가 온다. 토큰은 없다.
   - 대상: 새 가입, 그리고 확인 번호가 없는 기존 회원((가)).
   - 이미 확인된 회원은 바로 `AUTH_MEMBER`다.
   - `/completions` 응답도 확인이 남았으면 `AUTH_IDENTITY`다(전에는 늘 AUTH_MEMBER).
2. `POST /auth/social-logins/{authRequestId}/identity-verifications`
   - 헤더: `Idempotency-Key`·`X-Source-System`·`X-Device-Id`
   - 본문: `{providerToken, phone}`. providerToken은 로그인한 그 소셜 계정의 ID 토큰이다. `/completions`와 같고, 만료되면 401이니 새로 받는다.
   - 201 응답: `{verificationId, sendTo, text, expiresAt, phoneMasked, maxAttempts}`
   - 화면: "**{sendTo}로 {text}를 문자로 보내 주세요**" + 문자 앱 열기 버튼 + "보냈어요" 버튼. 고객이 직접 보내는 방식(MO)이고 우리는 문자를 보내지 않는다. 0.9.0 화면을 그대로 쓰면 된다.
   - 오류:
     - 010 11자리가 아님 → 400 `PHONE_NOT_SUPPORTED`
     - 하루 시작 횟수 초과 → 429 `TOO_MANY_VERIFICATIONS`
     - 다른 기기·다른 소셜 계정 → 403
3. `POST /auth/social-logins/{authRequestId}/identity-verifications/{verificationId}/confirmations`
   - 본문: `{providerToken}`. "보냈어요"를 누를 때만 부른다(조회마다 OCTOMO 월 한도가 준다).
   - 결과:

| 응답 | 뜻 | 앱 |
|---|---|---|
| 200 `AUTH_MEMBER` + tokens | 가입·로그인 완료 | 로그인 마침(기존 completions와 같은 처리) |
| 200 `AUTH_IDENTITY` + `identityVerification.state=PENDING` | 아직 문자가 확인되지 않음(`remainingAttempts`) | "아직 문자가 확인되지 않았어요" — 잠시 뒤 다시 |
| 200 `AUTH_IDENTITY` + `FAILED`(횟수 다 씀)·`EXPIRED`(시간 지남) | 이 확인은 끝 | 2번부터 다시 |
| 409 `IDENTITY_ALREADY_REGISTERED` | 그 번호로 가입한 다른 계정 | 아래 R3 |
| 409 `IDENTITY_REJOIN_BLOCKED` | 탈퇴한 번호(30일 안) | 아래 R4 |

   - 같은 Idempotency-Key 재전송(응답 유실)은 새 토큰을 준다. 다른 키로 이미 끝난 요청은 409 `INVALID_STATE`다.
4. 원인증 결과 조회(`GET /auth/social-logins/{id}`)도 `AUTH_IDENTITY`·`VERIFY_IDENTITY`를 준다.

## 답 (R1~R7)
- **R1 가입 단계·순서:** **필수 동의 → 휴대폰 확인**이다. 번호를 OCTOMO(처리 위탁)로 확인하기 전에 개인정보 동의를 먼저 받는다. 확인을 마쳐야 토큰이 나온다.
- **R2 한 번호 한 계정:** 서버가 막는다. 유효 번호는 한 계정만 쓸 수 있고, DB 고유 색인으로 동시 가입까지 막는다.
  - 0.9.0 `/me/phone-verifications`로 확인해도 같은 규칙이다. 다른 계정의 번호면 FAILED `PHONE_ALREADY_REGISTERED`이고, 이때는 기존 계정 안내 없음.
  - **0.9.0이 막지 않던 것을 0.10.0부터 막는다.**
  - 이메일로는 합치지 않는다(그대로).
- **R3 기존 계정 안내:** 409 `IDENTITY_ALREADY_REGISTERED`의 `details`(공통 오류 형식, field·reason 쌍)에 아래 값이 실린다.
  - `provider`: GOOGLE·KAKAO·NAVER
  - `joinedAt`: ISO 시각
  - `emailMasked`: 예 `a***@g***`, 이메일이 없으면 빠진다
  - **문자 확인을 통과한 본인에게만** 준다. 번호만 넣어 보는 것으로는 알 수 없다. 계정 ID·전체 번호는 주지 않는다.
  - 이번에 새로 만든 계정은 서버가 버린다. 그 소셜 계정으로 다시 로그인하면 새 가입부터다.
  - 앱 문구 예: "이 번호로 {카카오}로 가입한 계정이 있어요({가입일}·{가린 이메일}) — {카카오}로 로그인해 주세요" + 그 제공자 로그인 버튼.
- **R4 탈퇴 1달:** 409 `IDENTITY_REJOIN_BLOCKED`의 `details`에 `rejoinAvailableAt`가 실린다. 기간은 30일이다(라이더와 같음, 설정).
  - **고객 탈퇴 기능은 아직 없다**(`/me/withdrawals` 501). 탈퇴를 만들 때 번호를 풀게 하고, 막기 판정은 이미 들어 있다.
  - 지금은 이 응답이 실제로 나오지 않는다.
- **R5 기존 회원:** 사용자 결정 (가)다. 확인 번호가 없는 기존 회원은 **다음 로그인 때 `AUTH_IDENTITY`**(newAccount=false)가 온다.
  - 0.9.0으로 이미 확인한 회원은 그대로 들어간다. 배포 때 확인 번호로 옮긴다.
  - '내 정보'의 번호 확인 화면은 남겨도 된다. 번호를 바꾼 뒤 다시 확인하는 용도다.
- **R6 주문 연락처:** 확인된 번호가 프로필 `phone`·`phoneVerified=true`가 된다. 주문 받는 분 연락처 기본값(BR-60)도 이 번호다.
  - 고객이 프로필 번호를 바꾸면 미확인이 된다(0.9.0 그대로). 로그인 기준의 확인 번호는 남는다.
- **R7 처리방침:** 지금 `PRIVACY_POLICY:2026-11-17`에는 아래 세 가지가 없다.
  - 휴대폰 번호(수집 항목)
  - OCTOMO(주식회사 옥토버스) 위탁
  - 탈퇴 뒤 30일 번호 보관(재가입 막기)
  - 새 판이 필요하고, 새 판을 내면 **모든 회원이 다음 로그인 때 다시 동의**해야 한다(필수 동의 버전이 바뀌므로). 그래서 **사용자 결정을 받은 뒤** 문구를 바꾼다.
  - 정해지면 버전과 날짜를 알린다. 앱이 할 것은 없다(동의 화면은 서버 목록대로 나온다).

## 앱이 할 것
1. `AUTH_IDENTITY` 상태와 휴대폰 확인 화면: 번호 입력 → 보낼 문자 안내 → "보냈어요" → 확인. 0.9.0 화면을 이 두 경로로 바꿔 붙인다.
2. `/completions` 응답이 `AUTH_IDENTITY`일 수 있다(동의 다음 확인).
3. "이미 가입된 계정이 있어요"(R3)와 "탈퇴한 번호는 {날짜}부터 가입할 수 있어요"(R4) 화면.
4. 목 모드로 먼저 만든다. LAN은 서버 배포와 스위치를 켠 뒤 확인한다. 켜는 때는 앱이 반영됐다고 알려 주면 사용자 허락을 받아 켠다.

## 상태
- 2026-11-20: 회신을 보냈다. 서버 커밋은 이 회신과 같은 커밋이다. LAN 배포·스위치는 아직이다.
