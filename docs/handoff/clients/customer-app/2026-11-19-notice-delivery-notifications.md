# 알림 — 배송 진행 알림을 레퍼런스(10. 알림 3-1~3-5)에 맞춤

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 근거: 사용자 2026-11-19 "레퍼런스 이미지 참고해서 상품준비·배송시작 등 구현되어 있는지 확인" → 확인 결과(아래 표) → "응 해줘"
- 상태: LAN 배포됨 2026-11-19 14:08~14:11(이미지 26aa0f2b — notification 0.5.2·order 0.14.1, pmart-config 1dede52). **3-3·3-4 는 delivery(PC 5) 사건이 나와야 보인다**(요청 DE-6·TR-4).

| 레퍼런스 | templateKey | 제목 | 휴대폰 푸시 | 누르면(destination) |
|---|---|---|---|---|
| 3-1 준비중 | `PREPARATION_STARTED` | 예약이면 "예약하신 주문을 준비 중이에요", 아니면 "주문하신 상품을 준비 중이에요" — 본문 "주문하신 상품(사과 1kg 외 4개)을 준비하고 있어요." + 예약이면 "11월 20일 08:00~09:00 사이에 도착할 예정이에요" | **이제 보냄** | `ORDER`(배송이 아직 없을 수 있다 — 앱이 그 주문의 배송 현황을 열 수 있으면 열어 달라, 아니면 주문 상세) |
| 3-2 배달 시작 | `DELIVERY_STARTED` | 배송이 출발했어요 | 보냄(전과 같음) | **`DELIVERY` = deliveryId 로 바뀜** → 배송 현황(`/deliveries/:deliveryId`). 옛 알림은 ORDER |
| 3-3 배달 중 | `DELIVERY_HEADING`(새) | 주문하신 곳으로 가고 있어요 | 보냄 | `DELIVERY` |
| 3-4 곧 도착 | `DELIVERY_ARRIVING_SOON`(새) | 라이더님이 곧 도착해요! | 보냄 | `DELIVERY` |
| 3-5 배달 완료 | `DELIVERY_COMPLETED` | 배송이 완료되었어요 | 보냄 | `ORDER`(주문 상세 — 레퍼런스와 같음) |
| 3-6 리뷰 | `REVIEW_REQUESTED` | 받은 상품은 어떠셨나요?(문구 그대로 — 포인트 적립 없음) | **이제 보냄 — 배송 완료 10분 뒤**(전 1시간·알림함만) | ORDER → 리뷰 쓰기 |

## 앱이 할 것
- 새 templateKey 두 개(`DELIVERY_HEADING`·`DELIVERY_ARRIVING_SOON`)를 알림함·푸시에서 받기 — destination 이 `DELIVERY` 라 지금 `push_route.dart` 의 DELIVERY 처리(배송 현황)로 가면 된다.
- `DELIVERY_STARTED` 를 누르면 이제 배송 현황으로 간다(destination DELIVERY). 주문 상세로 보내던 처리가 templateKey 로 고정돼 있으면 destination 을 따르게.
- (선택) `PREPARATION_STARTED` 를 누르면 그 주문의 배송 현황 — 배송 ID 는 주문 상세 응답에서 찾을 수 있으면.
- 문구는 서버가 정한다(배송지 이름 "우리집" 등은 개인정보라 넣지 않음).
