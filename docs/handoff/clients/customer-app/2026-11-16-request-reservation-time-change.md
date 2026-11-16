# [pmart-backend → pmart-clients] 요청: 예약배달 시간 변경 화면 (2026-11-16)

- 보낸 영역: delivery(PC 5 — 플랫폼 영역 PR 로 PC 1 검토)
- 받는 앱: customer_app (주문 상세 → 예약 시간 변경, Commerce `SCR-ORDER-DETAIL-001.RESCHEDULE`·`SCR-RESCHEDULE-001`)
- 근거: CP-1 결정(2026-11-15 "고객이 원하면 다른 시간대로"), 업무 원문 OMS §4·Commerce §9.4, 계약 `order-service.yaml` 0.10.0

## API
| 할 일 | 호출 |
|---|---|
| 바꿀 수 있는 시간 보기 | 기존 `GET /api/v1/slots?mfcId=&date=`(오늘·내일) — 주문의 `mfcId`, 주문 상태 CONFIRMED·예약배달일 때만 메뉴를 보인다 |
| 변경 요청 | `POST /api/v1/orders/{orderId}/reservation-time-changes` `{newArrivalWindow:{date,start,end}}` + `Idempotency-Key`·`X-Source-System`(선택 `X-Expected-Version` = 주문 version) → **202**, `resultId` = requestId. 접수일 뿐 변경 성공이 아니다 |
| 결과 확인 | `GET /api/v1/orders/{orderId}/reservation-time-changes/{requestId}` → `state` PENDING(확인 중) / CONFIRMED(바뀜 — 주문 상세 다시 읽기) / REJECTED(기존 시간 그대로, `reason`) |

- 거절(요청 단계): 409 INVALID_STATE(상품 준비 시작 뒤), 422 NOT_SCHEDULED(즉시 배송), 422 SAME_SLOT, 409 TIME_CHANGE_IN_PROGRESS.
- REJECTED reason 예: CAPACITY_EXCEEDED·SLOT_NOT_SELECTABLE(새 시간대 마감), OPERATION_RESTRICTED·CAPACITY_EXCEEDED(센터 여력), LOT_EXPIRY_NOT_MET(신선 상품 기한 — 날짜를 바꿀 수 없음), PREPARATION_STARTED(그사이 상품 준비 시작). 화면에는 "기존 시간이 그대로 유지돼요"와 함께.
- 결제·주소·연락처는 바뀌지 않는다(새 결제 없음).
- 알림(시간이 바뀌었어요)은 아직 없다 — 결과는 조회로.
