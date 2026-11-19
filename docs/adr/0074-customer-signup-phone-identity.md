# ADR-0074 고객 가입·로그인 휴대폰 확인 (한 번호 한 계정·기존 계정 안내·재가입 30일)

- 상태: **승인** (2026-11-20)
- 사용자 결정 2026-11-19: "가입하자마자", "한 번호 한 계정", "안내에 제공자·가입일·가린 이메일 전부", "탈퇴 1달 재가입 금지", "처음 가입한 제공자 하나로만"
- 사용자 결정 2026-11-20: 기존 회원은 "(가) 다음 로그인 때 막고 확인"
- 근거: 고객 앱 BR-64 R1~R7
  - pmart-clients `docs/handoff/customer-app/2026-11-19-backend-social-login-identity.md`
  - 같은 폴더 `2026-11-19-backend-phone-verification-signup.md`
- 관련: ADR-0019·0055·0071(고객 소셜 로그인), CU-1(필수 동의 단계), customer 0.9.0(OCTOMO 휴대폰 확인 — 이미 로그인한 회원용)

## 배경
- 0.9.0 휴대폰 확인은 **이미 가입한 회원**이 고객 토큰으로 하는 기능이다.
- 사용자 결정은 다음 세 가지다.
  - 가입이 휴대폰 확인을 마쳐야 끝난다.
  - 같은 번호로 다른 계정이 있으면 새로 만들지 않고 그 계정을 안내한다.
  - 탈퇴한 번호는 1달 동안 다시 가입하지 못한다.
- 그래서 확인을 **토큰이 없는 로그인 단계 안**으로 옮긴다.

## 결정
1. **순서는 필수 동의 → 휴대폰 확인 → 회원 토큰이다.**
   - 번호를 OCTOMO(처리 위탁)로 확인하기 전에 개인정보 동의를 받는다.
   - `POST /auth/social-logins`의 상태에 `AUTH_IDENTITY`(nextAction `VERIFY_IDENTITY`)를 더한다. 이 상태에서는 토큰·세션·`CustomerAuthenticated`를 내지 않는다.
   - 동의 완료(`/completions`) 뒤 확인이 필요하면 `AUTH_IDENTITY`로 넘어간다.
2. **대상:**
   - 새 가입
   - 휴대폰 확인이 없는 기존 회원(사용자 (가) — 다음 로그인 때)
   - 확인된 회원은 같은 제공자로 다시 로그인하면 바로 들어간다.
3. **확인은 원인증 요청 번호로 한다.** 약관 동의 완료와 같은 방식이다.
   - `POST /auth/social-logins/{id}/identity-verifications`
     - 본문: `providerToken`·`phone`
     - 같은 기기에서, 같은 소셜 계정을 다시 검증한다.
     - 돌려주는 값: 보낼 숫자·대표번호·만료
   - `POST /auth/social-logins/{id}/identity-verifications/{verificationId}/confirmations`
     - 본문: `providerToken`
     - 고객이 대표번호로 문자를 보냈으면 확인한다.
     - 통과하면 원인증을 `AUTH_MEMBER`로 바꾸고 토큰을 준다.
     - 아직이면 확인 상태(남은 횟수 등)를 준다.
4. **번호 정본은 customer-service다.**
   - 표: `customer_identity`(계정마다 확인된 번호 — 해시·원문, 확인 시각, 풀린 시각)
   - **유효한 번호는 한 계정만** 쓴다. 해시에 부분 고유 색인을 걸어 동시성까지 막는다(R2·R4).
   - auth는 customer 내부 API로 묻는다. 계정·번호 표에 직접 접근하지 않는다.
   - `GET /internal/accounts/{id}/identity`
   - `POST /internal/accounts/{id}/identity-verifications`
   - `POST /internal/accounts/{id}/identity-verifications/{vid}/confirmations`
5. **확인이 통과해야만 결과를 알려 준다.** 번호를 넣어 보는 것만으로 남의 가입 여부를 알아낼 수 없게 하려는 것이다.
   - 그 번호가 다른 유효 계정에 있으면 → 409 `IDENTITY_ALREADY_REGISTERED`
     - details: `provider`(그 계정의 가입 제공자), `joinedAt`(회원이 된 때), `emailMasked`
     - 계정 ID와 전체 번호는 주지 않는다. 라이더(rider 0.17.0)와 같은 모양이다.
   - 탈퇴로 풀린 지 30일 안의 번호면 → 409 `IDENTITY_REJOIN_BLOCKED`
     - details: `rejoinAvailableAt`
     - 기간은 설정 `pmart.customer.identity.rejoin-block`(P30D)이다. 사용자 "1달" → 30일, 라이더와 같다.
   - 두 경우 모두 **이번에 새로 만든(회원이 된 적 없는) 계정과 제공자 연결은 지운다**(R2 "만들었으면 버린다"). 같은 소셜 계정으로 다시 로그인하면 다시 가입 단계부터다.
   - 기존 회원이 다른 계정의 번호를 넣으면 같은 안내만 하고 계정은 둔다. 다른 번호로 확인하면 된다.
6. **연결 없음(R5 원 요청):** 계정 하나에 제공자 하나다. 지금과 같다(이메일·번호로 합치지 않는다).
7. **R6:** 확인된 번호는 프로필 `phone`·`phoneVerified=true`가 된다. 주문 받는 분 연락처 기본값(BR-60)도 이 번호다.
   - 회원이 나중에 프로필 번호를 바꾸면 미확인이 된다(0.9.0 그대로). 계정의 확인 번호(로그인 기준)는 남는다.
   - `/me/phone-verifications`로 새 번호를 확인하면 확인 번호도 그 번호로 바뀐다. 다른 계정에 있는 번호면 FAILED `PHONE_ALREADY_REGISTERED`이고, 이 경우는 안내하지 않는다.
8. **기존에 확인된 회원**(0.9.0으로 `phone_verified=true`)은 마이그레이션으로 `customer_identity`를 채운다. 같은 번호가 둘이면 먼저 확인한 쪽만 넣는다.
9. **스위치:** `pmart.auth.customer.require-identity`(기본 false)다. 고객 앱이 새 단계를 반영하기 전에 켜면 로그인이 끝나지 않는다. require-consent와 같은 방식이다. LAN은 앱 반영 뒤 사용자 허락을 받고 켠다.
10. **탈퇴:** 고객 탈퇴는 아직 없다(customer `/me/withdrawals` 501). 탈퇴를 만들 때 `customer_identity.released_at`을 채운다. 재가입 막기 판정은 이미 그 값을 본다.
11. **R7(처리방침):** 새 판 초안 `customer-service/src/main/resources/consent-terms/PRIVACY_POLICY/2026-11-20.md`(2026-11-20, 사용자 "너가 알아서 작성해줄수있나")에 아래를 넣었다.
    - 휴대폰 번호·확인 기록(목적: 본인 번호 확인·중복 가입 방지·기존 계정 안내·주문 연락처)
    - 리뷰(별점·글·사진 — 11-16부터 받고 있었는데 빠져 있었다)
    - 주식회사 옥토버스(OCTOMO) 처리 위탁
    - 확인 번호 탈퇴 후 30일 보관
    - **아직 발효하지 않았다.** 발효는 customer `consents.versions.privacy-policy`를 이 판으로 바꾸는 설정 변경이고, 바꾸면 모든 회원이 다음 로그인 때 다시 동의한다. `require-identity`를 켜는 날 같이 바꾼다(사용자 권장 동의). 이 파일이 든 customer 이미지를 먼저 배포해야 본문이 나온다.
    - 시행일 줄은 실제 발효일로 고친다. 법률 검토는 사용자 몫이다.
    - **할 일:** 고객 탈퇴를 만들 때 `customer_identity`의 풀린 번호를 30일 뒤 지운다(처리방침 약속).

## 결과
- 가입과 미확인 회원 로그인이 한 단계 늘어난다(문자 보내기 1회).
- OCTOMO 월 조회 한도를 고객 가입이 같이 쓴다(확인 버튼을 누를 때만 조회한다).
- 번호 원문은 customer DB에만 있다(`customer.phone`·`customer_identity.phone`). 로그·사건에는 싣지 않는다.
