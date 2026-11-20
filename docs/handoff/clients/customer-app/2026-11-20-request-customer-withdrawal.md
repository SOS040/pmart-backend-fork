# 요청 — 고객 앱 회원 탈퇴 화면 (customer 0.12.0, ADR-0077)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-20
- 근거: 사용자 "고객 탈퇴 만들어줘"(2026-11-20), 결정 — 재인증 = 같은 소셜로 다시 로그인, 의무 확인 = order + claim, 소셜 식별값 = 회원 표와 분리 보관
- 정본: `docs/adr/0077-customer-withdrawal.md`, 계약 `contracts/openapi/customer-service.yaml` 0.12.0(태그 `withdrawals`)
- 상태: 서버 구현·시험 끝, LAN 배포는 이 문서 아래 "배포 상태" 줄 참고
- 지금 앱: 회원 탈퇴 화면·API 호출 없음(origin/main 확인 2026-11-20)

## 1. 들어가는 곳
- 마이 → 계정(또는 설정) → "회원 탈퇴". Commerce 문서 31개 화면의 보조 이동 "내 쇼핑에서 계정/탈퇴".

## 2. 탈퇴 접수
1. 안내 화면: 탈퇴하면 지워지는 것(이름·연락처·주소록·장바구니·알림 기기), 남는 것(주문·결제·환불 기록은 법정 기간 보관), 진행 중 주문·환불이 있으면 끝날 때까지 "탈퇴 처리 대기"가 된다는 것, 재가입은 새 계정·확인한 번호는 30일 동안 재가입 불가.
2. 사유(선택) — `reasonCode`(최대 40자, 앱이 정한 코드)·`reasonText`(최대 500자).
3. **재인증**: 지금 로그인한 그 소셜(카카오·네이버·구글)로 다시 로그인해 **ID 토큰**을 받는다(로그인 때와 같은 방법 — 네이버는 OIDC id_token). 다른 소셜 계정으로 로그인하면 403.
4. `POST /api/v1/me/withdrawals` — 헤더 `Idempotency-Key`·`X-Source-System`, 본문 `{provider, providerToken, reasonCode?, reasonText?}`.
   - 202 + `Withdrawal`: `state` 가
     - `COMPLETED` → 탈퇴 끝. 이 뒤 토큰·리프레시는 더 쓸 수 없다(서버가 세션을 끊고 토큰을 막음) — 로컬 로그인 정보 지우고 로그아웃 상태 홈으로.
     - `BLOCKED_BY_OBLIGATIONS` → "탈퇴 처리 대기" 화면. `openObligations[]`(kind·ref)로 사유 안내: `ORDER_IN_PROGRESS`·`PAYMENT_UNKNOWN`(주문 ref = orderId → 주문 상세로), `CLAIM_IN_PROGRESS`·`REFUND_IN_PROGRESS`(ref = claimId → 클레임 상세로).
     - `UNKNOWN` → "탈퇴 확인 중"(서버가 다시 확인함 — 거래가 없다고 보지 않음).
   - 401 `PROVIDER_REJECTED`(재인증 토큰 거절 — 다시 로그인), 403 `REAUTHENTICATION_MISMATCH`("이 계정에 연결된 소셜로 다시 로그인해 주세요"), 409 `INVALID_STATE`(이미 신청함 → 상태 화면으로), 503(잠시 뒤 다시).

## 3. 탈퇴 대기 중
- 로그인은 된다. **신규 구매는 막힌다** — 주문서·결제 진입은 기존처럼 422 `ACCOUNT_NOT_PURCHASABLE`(계정 상태 `WITHDRAW_PENDING`). 주문 조회·클레임·고객센터는 그대로 쓴다(막지 말 것).
- 상태 보기: `GET /api/v1/me/withdrawals`(최근 것부터 `items[]`) 또는 `GET /me/withdrawals/{withdrawalId}`. 서버가 10분마다 다시 확인해 의무가 끝나면 스스로 완료한다 — 앱이 다시 접수할 필요 없음.
- `GET /me` 의 계정 상태가 `WITHDRAW_PENDING` 이면 마이 상단에 "탈퇴 처리 대기" 띠 + 상태 화면 링크.
- 완료되면 다음 API 호출이 401 → 로그인 정보 지우고 "탈퇴가 완료됐어요" 안내(가능하면).

## 4. 탈퇴 철회
- 대기 중(`BLOCKED_BY_OBLIGATIONS`·`UNKNOWN`·`PENDING`)일 때만. 같은 소셜 재인증 뒤 `POST /api/v1/me/withdrawals/{withdrawalId}/revocations` 본문 `{provider, providerToken}` → 200 `state: REVOKED`, 계정은 다시 정상(구매 가능).
- 마케팅 수신 동의는 되돌리지 않는다 — 원하면 알림 설정에서 다시 켜도록 안내.
- 완료된 탈퇴는 철회 불가(409).

## 5. 재가입
- 탈퇴 완료 뒤 같은 소셜로 로그인하면 **새 계정**(가입·약관·휴대폰 확인 처음부터). 30일 안에 같은 번호면 휴대폰 확인에서 409 `IDENTITY_REJOIN_BLOCKED`(이미 처리하는 화면 — R4).

## 6. 로그·개인정보
- `providerToken`·사유 원문은 로그에 남기지 않는다(앱 요청 로그의 본문 가리기 확인).

## 배포 상태
- **LAN 배포 2026-11-20 15:01 KST**: customer·order·claim·cart(PC 2), auth·notification·api-gateway(PC 4) b9c7aeca, pmart-config c3ad4d6. 게이트웨이 `GET /api/v1/me/withdrawals` 토큰 없이 401(경로 열림) 확인. 실제 탈퇴는 앱 화면으로 확인.
