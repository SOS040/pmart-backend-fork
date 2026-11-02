# [pmart-backend → pmart-clients] 회신: 주문 당시 배송지·수령 연락처 사본 (BR-37) (2026-11-02)

- 요청: `pmart-clients/docs/handoff/2026-11-02-backend-order-customer-reference.md`
- 근거(확정 정책): OMS 153행 "주소·연락처·요청 사항 … 주문 당시 값으로 보존", Commerce 3294·3327행(확정 주문 = 주문 당시 배송지·수령 연락처), 3351행 "현재 수정된 주소로 과거 주문 수령 주소·연락처를 소급 변경하지 않는다", 86행 "결제 후 주문 연락처 변경 금지"
- 계약: `contracts/openapi/order-service.yaml` `ShippingAddress`(신규), `DraftDelivery.shippingAddress`·`Order.shippingAddress`(신규), `CreateDraftRequest.contact` deprecated. `customer-service.yaml` 주소 수정·삭제 설명 보완.
- 구현: order-service(V5 `customer_order_shipping`). LAN 반영은 이 문서 끝 참조.

## 1. 질문에 대한 답
1. **"확정 주문의 주소는 변경되지 않음"은 어떻게 보장되나** — 읽으신 대로 **지금까지는 보장되지 않았다(미구현, (a))**. 이번에 고쳤다.
   - **초안을 만들 때**(`POST /order-drafts`) customer-service 의 그 배송지(도로명·상세·우편번호·수령인·연락처·출입 방법/메모·지도 핀)를 **복사해 초안에 고정**한다. 결제 진입·주문 확정은 이 사본을 쓴다(주문서에서 고객이 본 값 = 주문 값).
   - 주문 확정 때 사본을 주문으로 옮긴다. 이후 주소록을 고치거나 지워도 바뀌지 않는다.
   - 고객용 조회: **`GET /orders/{orderId}` 의 `shippingAddress`**. 주문서 확인용으로 초안 응답의 `delivery.shippingAddress` 에도 같은 값이 있다.
   - 가림: 본인 조회라 **가리지 않는다**(주소록 `GET /me/addresses/{id}` 와 같은 수준). `entranceNote` 는 출입 비밀번호일 수 있어 앱도 로그·분석 이벤트에 넣지 않는다. 주문 목록(`OrderSummary`)에는 싣지 않는다.
2. **확정 주문·미결 클레임이 참조하는 주소를 지울 수 있나** — 지울 수 있고, 그래도 주문은 영향이 없다(주문이 사본을 가진다). 그래서 삭제 규칙은 지금처럼 "현재 쇼핑 주소만 막음"으로 둔다. 반품 회수 주소도 주문 사본을 기준으로 한다(클레임 구현 때).
3. **수령인·연락처의 주문 시점 고정** — 업무 문서가 이미 정했다: 주문 당시 값으로 고정, 결제 후 주문 연락처 변경 금지. 수령인·연락처는 **선택한 배송지의 수령인·연락처**를 사본으로 쓴다.
   - `CreateDraftRequest.contact.phoneRef` 는 **쓰지 않는다**(deprecated). 보내도 무시한다. 안심번호는 지금 없다 — 도입하면 따로 알린다.
   - 본인 연락처·기본 수령정보(BR-32)는 주소록 입력 편의용이다. 주문은 배송지의 수령인·연락처를 쓴다.
4. **구매 가능 계정 검증 시점** — **결제 진입 때 한 번 더 본다**(백엔드 사용자 결정 2026-11-02). 초안 뒤 계정이 구매 불가(정지·탈퇴 진행 등)로 바뀌면 `POST /order-drafts/{id}/checkouts` 가 **422 `ACCOUNT_NOT_PURCHASABLE`** 를 준다(사가를 시작하지 않음, 결제창 없음). 앱은 "지금 주문할 수 없는 계정 상태" 안내로 처리한다. 이미 진행 중이거나 확정된 결제 진입은 그대로다.

## 2. 응답 형태
```json
"shippingAddress": {
  "roadAddress": "부산광역시 부산진구 중앙대로 708",
  "detailAddress": "101동 1001호",
  "postalCode": "47296",
  "receiverName": "홍길동",
  "receiverPhone": "01012345678",
  "entranceMethod": "PASSWORD",
  "entranceNote": "#1234",
  "geo": { "lat": 35.1577, "lng": 129.0599 },
  "capturedAt": "2026-11-02T07:10:00Z"
}
```
- **`null` 이 올 수 있다**: 이 변경 전(2026-11-02 배포 전)에 만든 초안·주문은 사본이 없다. 그때는 요청서 §3 대로 "배송지 정보 없음"으로 두고 `shoppingAddressId` 로 현재 주소를 대신 보이지 않는다.
- `postalCode`·`entranceMethod`·`entranceNote`·`geo` 는 주소록에 없으면 `null`.

## 3. 앱에 부탁
- 주문 상세·취소·반품 화면의 배송지는 `shippingAddress` 만 쓴다(`shoppingAddressId` 로 주소록을 다시 부르지 않는다).
- 주문서 화면은 초안의 `delivery.shippingAddress` 로 "이 주소로 배달" 을 보여 줄 수 있다. 주소를 바꾸려면 지금처럼 새 초안을 만든다(사본은 초안마다 고정).

## 4. LAN 반영
- **LAN 반영 2026-11-02 17:18 KST** — pmart-backend `312a51b`(order·payment·api-gateway 이미지), pmart-config `275f84a`. order-service V5(`customer_order_shipping`) 적용, 세 서비스 UP. 게이트웨이 확인: `/api/v1/payment-widget-config` 토큰 없음 401·비회원 403. 회원 토큰 200 은 앱에서 확인 부탁(Google 로그인 필요).
- 이 배포 전에 만든 초안·주문은 `shippingAddress` 가 `null` 이다.
