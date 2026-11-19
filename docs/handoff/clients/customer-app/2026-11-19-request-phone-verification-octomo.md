# 요청 — 휴대폰 번호 확인 화면 (OCTOMO, customer-service 0.9.0)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 계기: 사용자 2026-11-19 "옥토모로" — 고객 휴대폰 번호 확인. 고객이 **직접 문자를 보내는** 방식(우리가 인증번호 문자를 보내지 않는다)
- 상태: 백엔드 main 반영, LAN 배포는 사용자 승인 뒤(배포되면 아래에 적는다)

## 흐름
1. 번호 저장은 지금처럼 `POST /me/profile-changes {phone}` → `GET /me` 의 `phoneVerified=false`.
2. "번호 확인" → `POST /me/phone-verifications`(Idempotency-Key·X-Source-System, 본문 없음) → 201
   `{verificationId, state: PENDING, sendTo: "1666-3538", text: "482913", expiresAt, phoneMasked, maxAttempts}`.
3. 앱이 **문자 앱을 받는 번호·본문을 채워 연다**(Android `sms:16663538?body=482913` — Intent ACTION_SENDTO). 안내: "이 번호로 아래 숫자를 그대로 보내 주세요(고치면 확인되지 않아요)", 남은 시간(expiresAt, 10분).
4. 고객이 보낸 뒤 **"보냈어요" 버튼을 누를 때만** `POST /me/phone-verifications/{verificationId}/confirmations` → 200 `{state, remainingAttempts, failureReason, verifiedAt}`.
   - `VERIFIED` → 완료(`GET /me` `phoneVerified=true`).
   - `PENDING` → "아직 문자가 도착하지 않았어요" + 남은 횟수. 문자 도착이 몇 초 늦을 수 있다.
   - `FAILED`(TOO_MANY_ATTEMPTS·PHONE_CHANGED)·`EXPIRED` → 처음부터 다시 시작.
   - 503 → "잠시 뒤 다시"(횟수 줄지 않음).
5. **자동으로 되풀이해 부르지 말 것** — 조회마다 OCTOMO 월 한도(무료 1만 건)가 준다.

## 오류
- 400 `PHONE_REQUIRED`(번호 먼저 저장)·`PHONE_NOT_SUPPORTED`(010 11자리만), 409 INVALID_STATE(이미 확인됨·탈퇴 신청 이후), 429 `TOO_MANY_VERIFICATIONS`(하루 10번), 422 CONFIG_REQUIRED(서버 설정 없음).
- 번호를 바꾸면 다시 미확인이 된다.

## 알아 둘 것
- 번호 소유만 확인한다 — 이름·나이 확인이 아니라 **주류 성인 확인에 쓰지 않는다**.
- 개인정보 처리방침에 OCTOMO(주식회사 옥토버스) 처리 위탁을 적어야 한다(약관 문구는 백엔드 consent-terms 쪽에서 따로).
