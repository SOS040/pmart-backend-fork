# 알림 — 새 고객 알림 종류 `DELIVERY_INCIDENT_RESHIPMENT` (사고·파손 재발송, 2026-11-19)

- 보낸 영역: platform (PC 1)
- 받는 앱: customer-app(알림함·푸시)
- 근거: ADR-0069(사고·파손이면 새 상품 재발송, 고객 비용 0원), 서비스 간 요청 N-4, 사용자 2026-11-19 "응"

## 무엇이 오나
- 배송 중 라이더 사고(RIDER_INCIDENT)·상품 파손(DAMAGED)으로 전달 실패가 확정되면 고객 알림함에 한 줄:
  - `templateKey` **`DELIVERY_INCIDENT_RESHIPMENT`**, 분류 DELIVERY, 목적지 `ORDER`(destinationRef = 주문 ID)
  - 제목 "배송 중 문제가 생겨 새 상품으로 다시 보내 드려요"
  - 본문 "배송 중 상품을 전달하지 못했어요. 추가 비용 없이 새 상품으로 다시 보내 드릴게요. 출발하면 다시 알려 드려요. 상품이 모자라면 그만큼 환불해 드려요."
- **휴대폰 푸시도 간다**(`fcm.customer-templates` 에 넣음) — data 의 templateKey·destinationType·destinationRef 는 다른 알림과 같다.
- 그 뒤 새 상품이 출발하면 기존 "배송 출발" 알림, 재고가 모자라 일부 환불되면 기존 결품·환불 알림이 따로 온다.
- 고객 부재·주소 오류 같은 다른 실패 사유는 알림이 없다(상담원이 안내).

## 앱에서
- 목적지 ORDER 라 주문 상세로 열면 된다. 새 아이콘·분기가 필요 없으면 할 일 없음 — 모르는 templateKey 를 기본 모양으로 보이는지만 확인.
- **LAN**: 배포 전(배포하면 이 줄을 고친다).
