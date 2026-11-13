# [pmart-backend → pmart-clients] 안내: 토스 결제위젯 customerKey = 회원 accountId (BR-34 일부, 결제 명세 D6) (2026-11-02)

- 관련: 요청 `pmart-clients/docs/handoff/clients/2026-10-31-backend-checkout-widget-and-product-names.md` §1(BR-34), 결제 명세 `docs/architecture/payment-system-spec.md` §10.3·§12 D6, 이전 안내 `2026-10-30-clients-notice-customer-payment.md`("customerKey 미정 — 앱 임시값")
- 결정(2026-11-02, 백엔드 사용자): **customerKey 로 회원의 기존 accountId 를 그대로 쓴다.** 별도 값·컬럼을 만들지 않는다.

## 1. 앱이 할 일
- `PaymentWidget(clientKey: …, customerKey: <accountId>)` — 지금 쓰는 `PaymentWidget.anonymous`(`@@ANONYMOUS`)를 **이 값으로 바꾼다.**
- accountId 는 이미 받고 있다:
  - `GET /api/v1/me` 응답 `accountId` (권장 — 위젯을 열 때 현재 로그인 계정 기준)
  - 또는 로그인 응답 `AuthResult.accountId`(AUTH_MEMBER 일 때)
- 값을 **가공하지 않는다**(자르기·대소문자 변경·접두어 제거 금지). 받은 문자열 그대로 넘긴다.
- accountId 가 없으면(비회원·로그인 전·`AUTH_CONSENT` 등) 위젯을 열지 않는다. 주문은 로그인 회원만 한다(OMS §4) — 비회원 익명 키로 결제를 열지 않는다.

## 2. 값 형식 (토스 규칙 충족 확인)
| 토스 customerKey 규칙(명세 §10.3) | accountId |
|---|---|
| 22~50자 | 39자 — `CU-` + UUID (예: `CU-3f2a9c1e-7b4d-4e8a-9c21-5d6f0a1b2c3d`) |
| `[A-Za-z0-9-_=.@]` 만 | 영문·숫자·`-` 만 |
| 무작위(이메일·전화·순번 금지) | UUIDv4 무작위 |
| 회원 계정당 1개 | 계정 ID |

## 3. 주의
- accountId 는 비밀번호는 아니지만 **로그·분석 이벤트·크래시 리포트에 넣지 않는다**(customerKey 로도 쓰이므로).
- 지금은 결제수단 저장(간편결제·브랜드페이 등)을 쓰지 않는다. 그 기능을 켜게 되면 백엔드가 customerKey 를 별도 무작위 값으로 바꿀 수 있다 — 그때 따로 알리고, 앱이 서버가 내려준 값을 쓰도록 경로(아래 §4)를 함께 준다.
- 탈퇴 후 재가입 계정은 새 accountId 를 받는다(탈퇴 구현 때 보장 — 지금 탈퇴는 501).

## 4. clientKey 는 그대로 (BR-34 나머지)
- clientKey 는 아직 결제 진입 뒤 `nextAction=PAY` 의 `payment.clientKey` 로만 온다. 주문서 화면에서 결제 진입 **전에** 위젯을 그리기 위한 조회 경로(`GET /payment-widget-config` → `{provider, clientKey, customerKey}`)는 다음 작업으로 만든다. 만들면 회신한다 — 그때도 customerKey 값은 이 안내와 같다(accountId).
