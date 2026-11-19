# 회신 — BR-60 배송지에서 받는 분·연락처 빼기

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: `pmart-clients docs/handoff/customer-app/2026-11-19-backend-address-receiver-to-checkout.md` R1~R4 (사용자 "응 해줘" — 추천대로)
- 상태: LAN 배포됨 2026-11-19 12:51~12:53(이미지 94517d87 — customer 0.8.0·order 0.14.0)

## R1 주소 입력에서 받는 분·연락처 선택 — 됨(customer 0.8.0, V11)
- `AddressInput.receiverName`·`receiverPhone` 필수 아님(회원 `POST·PATCH /me/addresses`, 비회원 `POST /guest-delivery-addresses` 모두). 응답 `Address` 의 두 값은 null 일 수 있다.
- **PATCH 는 전체 교체지만 두 칸을 빠뜨리면 저장된 값을 그대로 둔다** — 앱이 칸을 없애도 옛 주소의 값이 지워지지 않는다.

## R2 주문 초안 연락처는 회원 연락처에서 — 됨
- 주문 초안이 쓰는 배송지 사본은 **주소 값이 먼저, 없으면 프로필**: 받는 분 = 별명 → 로그인 제공자 이름, 연락처 = 프로필 `phone`(BR-32).
- 둘 다 연락처가 없으면 초안을 만들고 **막힘 `CONTACT_REQUIRED`**("받는 분 연락처를 입력해 주세요", 다른 막힘 뒤 순서). 결제 진입은 막힌다(`CHECKOUT_BLOCKED`).
- 앱: "내 연락처"를 받아 `POST /me/profile-changes {phone}` → 초안을 다시 만든다.

## R3 주문서 "번호 변경" — (가) 앱 추천대로
- 프로필 연락처를 바꾸고(`profile-changes`) 초안을 다시 만든다. 이 주문만의 연락처 칸(`contactPhone`)은 두지 않는다.
- 주의: 주소에 연락처가 저장돼 있으면 그것이 먼저다(옛 주소). 그 주소를 프로필 번호로 따르게 하려면 주소 수정(PATCH)에서 `receiverPhone: ""`(빈 문자열)을 보내면 된다 — 빈 값은 없는 것으로 보고 프로필 번호를 쓴다(받는 분 이름도 같다). 칸을 빼고 보내면 저장된 값이 남는다.

## R4 받는 분 이름이 없을 때 — 비워 둔다
- 별명·제공자 이름도 없으면(드묾) 사본 `receiverName` 은 null. 막지 않는다. 라이더 전달지(`drop-info`)·주문 응답의 `receiverName` 도 null 일 수 있다(order 0.14.0). 라이더 쪽(delivery-execution)은 빈 값으로 받는다 — 화면은 "이름 없음" 등으로.

## 앱이 할 것
- 회원·비회원 주소 입력에서 받는 분·연락처 칸을 뺀다(보내지 않아도 된다).
- 주문서: `CONTACT_REQUIRED` 면 "내 연락처" 입력 → `profile-changes` → 초안 다시. "번호 변경"도 같은 흐름.
