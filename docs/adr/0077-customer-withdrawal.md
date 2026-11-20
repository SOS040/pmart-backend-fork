# ADR-0077 고객 탈퇴 (재인증·의무 확인·완료 정리·소셜 식별값 분리 보관)

- 상태: **승인** (2026-11-20)
- 사용자 결정 2026-11-20: "고객 탈퇴 만들어줘", 재인증 = "소셜 다시 로그인", 의무 확인 = "order + claim 둘 다",
  소셜 식별값 = "마스터/활성 유저 테이블과 완전 분리해서 별도 테이블에 격리 보관(보관 목적 외 기능에서 끌어다 쓰지 않도록 통제, 만료 파기 관리가 쉬움)"
- 근거: `docs/business/Commerce-business-logic.md` §4.4(탈퇴·보존·재가입), §4.5 5번, `docs/business/OMS-business-logic.md` §13,
  개인정보 처리방침 2026-11-20판 §3(보관 기간), ADR-0074 10번(확인 번호 `released_at`·30일 뒤 파기)
- 관련: customer 0.12.0, order 0.16.0, claim 0.9.0, auth 0.9.0, cart 0.3.0, notification 0.6.0

## 결정
1. **재인증 = 같은 소셜로 다시 로그인.** 앱이 탈퇴 접수·철회 때 그 제공자로 다시 로그인해 받은 ID 토큰을 `provider`·`providerToken` 으로 함께 보낸다.
   - customer-service 는 토큰을 직접 검증하지 않고 auth-service 내부 API `POST /internal/customers/{accountId}/reauthentications` 에 묻는다.
   - auth 는 로그인과 같은 검증(서명·iss·aud·exp)을 하고, 그 제공자 계정이 **이 accountId 에 연결된 계정**인지 본다. 다르면 403 `REAUTHENTICATION_MISMATCH`, 토큰 거절은 401 `PROVIDER_REJECTED`.
   - auth 장애는 503(접수하지 않음 — 재인증 없이 탈퇴시키지 않는다).
2. **접수(ACTIVE → WITHDRAW_PENDING)** 는 즉시 신규 구매를 막는다(구매 가능 = ACTIVE 그대로). `CustomerWithdrawalRequested` 발행.
   - 탈퇴를 이유로 주문·예약을 자동 취소하지 않는다. 주문 조회·고객센터·클레임은 그대로 쓴다(로그인도 된다).
3. **의무 확인 = order + claim 둘 다.** customer 가 두 서비스의 `GET /internal/accounts/{accountId}/withdrawal-eligibility` 를 부른다.
   - order: 끝나지 않은 주문(취소·배송 완료·자동 구매확정 같은 끝 상태가 아닌 것), 결과 모르는 결제.
   - claim: 끝나지 않은 취소·반품·교환·환불·분쟁.
   - 하나라도 미결이면 `BLOCKED_BY_OBLIGATIONS`(미결 목록 사본), 조회 실패는 `UNKNOWN`(거래 없음으로 추정하지 않음). 주기 작업이 다시 조회한다.
4. **완료** (둘 다 eligible): customer 가 한 트랜잭션에서
   - 상태 WITHDRAW_COMPLETED, 이름·이메일·전화·별명·사진 정보 지움, 주소록·쇼핑 맥락·주문 기본값·기기 동의 옮김 기록 지움
   - 확인 번호 `customer_identity.released_at` = 완료 시각(30일 재가입 막기), 사진 사본(S3)은 트랜잭션 뒤 지움
   - 동의 이력·첫 구매 사용 기록은 그대로(증빙), customerNumber 는 남김(이력 표시용 — 개인정보 아님)
   - `CustomerWithdrawalCompleted` 발행 → auth(세션 끊기·소셜 연결 지움·계정 사용 안 함, 식별값 해시를 분리 테이블로), cart(회원 장바구니 지움), notification(고객 단말 푸시 토큰 지움)
5. **소셜 식별값 분리 보관(사용자 결정).** auth 의 `withdrawn_social_identifier` 테이블 — 회원 테이블과 외래키·조인 없음.
   - 값은 `SHA-256(provider:subject)` 만(원래 ID 는 남기지 않음), 탈퇴 완료일·만료일(완료 + 5년).
   - 읽는 곳은 첫 구매 혜택 중복 판정 하나로 제한(이번 범위에서는 저장·만료 파기만 — 판정 연결은 첫 구매 혜택을 만들 때).
   - 만료된 행은 주기 작업이 지운다.
6. **철회**: WITHDRAW_PENDING 에서만, 재인증 필요. ACTIVE 로 되돌리고 마케팅 동의는 복원하지 않는다(별도 재동의).
7. **파기 기한**: 풀린 확인 번호(`customer_identity` released 행)는 30일 뒤 주기 작업이 지운다(처리방침). 주문·결제 증빙 5년은 각 서비스 정본에 그대로(이번에 손대지 않음).

## 결과
- 고객 앱은 탈퇴 화면에서 소셜 재로그인 → `POST /me/withdrawals` → `GET /me/withdrawals`(최근 것)로 상태를 보여 준다.
- 재가입은 새 계정(같은 소셜이면 auth 가 새 계정을 만든다). 과거 주문·장바구니·주소록은 복원하지 않는다.
