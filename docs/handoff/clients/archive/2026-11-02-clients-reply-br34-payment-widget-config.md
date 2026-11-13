# [pmart-backend → pmart-clients] 회신: 주문서형 결제위젯 설정 — `GET /payment-widget-config` (BR-34) (2026-11-02)

- 요청: `pmart-clients/docs/handoff/clients/2026-10-31-backend-checkout-widget-and-product-names.md` §1 (BR-34). §2(BR-35 상품명 사본)는 따로 회신한다.
- 선택: 요청서의 (b) 별도 조회. 토스 키는 payment-service 설정이라 주문(초안) 계약에 결제사 정보를 섞지 않는다.
- 계약: `contracts/openapi/payment-service.yaml` `GET /payment-widget-config`, `PaymentWidgetConfig`. 게이트웨이 라우트 추가.
- 함께 볼 것: `2026-11-02-clients-notice-toss-customer-key.md`(customerKey = accountId)

## 1. API
```
GET /api/v1/payment-widget-config        (회원 고객 토큰, 게이트웨이 경유)
200  Cache-Control: private, max-age=3600
{ "provider": "TOSS_PAYMENTS", "clientKey": "test_gck_…", "customerKey": "CU-…" }
```
| 응답 | 뜻 | 앱 처리 |
|---|---|---|
| 200 | 위젯 설정 | `PaymentWidget(clientKey, customerKey)` |
| 403 `FORBIDDEN` | 회원 토큰이 아님(비회원·운영자) | 위젯을 열지 않는다 — 주문은 회원만(OMS §4) |
| 422 `CONFIG_REQUIRED` | 서버에 결제위젯 키가 없음 | "결제를 준비하지 못했어요" — 결제 진입하지 않는다 |

- `customerKey` 는 지금 회원 accountId 와 같다. **앱은 이 응답의 값을 쓴다**(나중에 별도 값으로 바뀌어도 앱 수정 없음). `GET /me` 의 accountId 를 쓰라는 앞 안내는 이 API 가 배포되기 전까지의 방법이다.

## 2. 언제 부르나 — 세션마다 한 번
- 결제마다 바뀌는 값(금액·orderId·주문명)은 여기 없다 — 지금처럼 결제 진입 뒤 초안 `payment` 로 받는다.
- 이 응답은 **로그인 세션마다 한 번 받아 메모리에 두고 재사용**한다. 주문서를 열 때마다 부르지 않는다.
- 다시 받을 때: 다른 계정으로 로그인했을 때, 로그아웃 뒤, 위젯이 키 오류를 낼 때.
- 값을 로그·분석 이벤트·크래시 리포트에 넣지 않는다.

## 3. 주문서형 흐름 (요청서 §1 "앱 흐름"과 같다)
1. (세션 첫 필요 시) `GET /payment-widget-config`
2. 주문서 화면에 위젯을 그린다. 금액은 초안 `quote.totalAmount`.
3. 결제하기 → `POST /order-drafts/{id}/checkouts` → 초안을 1초 간격으로 조회해 `PAY` 를 기다린다.
4. `requestPayment(orderId = payment.pgOrderId, orderName = payment.orderName)`.
   - 결제 진입 응답 `payment.clientKey` 도 계속 온다(호환). 위젯은 이미 1번 값으로 만들었으니 쓰지 않아도 된다.

## 4. 확인 요청에 대한 답 — `payment.amount` 는 항상 동의한 견적의 `totalAmount` 와 같은가
- **같다.** 결제 시작 금액은 결제 진입 때 확인한 동결 견적의 `totalAmount` 다(쿠폰 단계는 1차 생략). 견적이 바뀌었거나 10분 동결이 지났으면 결제 진입 자체가 `422 RE_QUOTE_REQUIRED` 로 거절된다.
- 그래도 다르면 앱은 요청서 계획대로 `updateAmount` 하지 말고 결제를 멈춘다. 서버도 승인 금액 ≠ 청구액이면 확정하지 않고(대사), 주문 확정 단계에서 다시 막는다.
- 결제 진입 때 계정 상태도 다시 본다 — 구매 불가로 바뀌었으면 `422 ACCOUNT_NOT_PURCHASABLE`(BR-37 회신 4번).

## 5. LAN 반영
- **LAN 반영 2026-11-02 17:18 KST** — pmart-backend `312a51b`(order·payment·api-gateway 이미지), pmart-config `275f84a`. order-service V5(`customer_order_shipping`) 적용, 세 서비스 UP. 게이트웨이 확인: `/api/v1/payment-widget-config` 토큰 없음 401·비회원 403. 회원 토큰 200 은 앱에서 확인 부탁(Google 로그인 필요).
- 이 배포 전에 만든 초안·주문은 `shippingAddress` 가 `null` 이다.
