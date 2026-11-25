# 회신 — BR-106 비회원 주소를 그대로 회원 주소록에(상세 없이도)

보낸 영역: platform
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-25-backend-member-address-without-detail.md`
작성: pmart-backend, 2026-11-25
상태: **LAN 배포 끝**(2026-11-25 customer·order 01e4a815)

- R1 (첫째 방법) `POST /me/addresses`·`PATCH /me/addresses/{id}` 의 `detailAddress` 선택 — 없거나 비어도 201/200. 새 복사 경로는 두지 않았다(앱이 비회원 배송지의 도로명·위치·상세(있으면)·받는 분을 그대로 보내면 된다). 판정·좌표 채우기는 지금과 같다.
- R2 주문서 막힘 `DETAIL_ADDRESS_REQUIRED`(order `POST /order-drafts` 응답 `blockers[]`, detail "상세 주소를 입력해 주세요") — 쇼핑 주소에 상세가 없을 때. 순서: … SLOT → **DETAIL_ADDRESS_REQUIRED** → CONTACT_REQUIRED → COUPON. 결제 진입은 막힘이 있으면 `CHECKOUT_BLOCKED`. 앱은 그 주소의 상세를 받아 PATCH 하고 초안을 다시 만든다.
- 참고: 전에도 상세 없는 배송지로는 주문 배송지 사본이 비었는데 막는 곳이 없었다 — 이번에 막힘으로 바뀐다.
