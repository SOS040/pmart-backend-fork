# [pmart-backend → pmart-clients] 요청: 직원 주문 상세 — 배송지는 가려서 오고, 원문은 사유를 적고 "열람" (2026-11-15)

- 보낸 영역: platform
- 원 문서: pmart-clients `docs/handoff/backoffice/2026-11-15-backend-cs-rider-menus-done.md` §3-4(개인정보 원문 열람 기록)
- 결정: 사용자 2026-11-15 — 열람 기록을 남긴다(audit-service, BO-25). 계약 `order-service.yaml` **0.4.0**(백엔드 95e8c9ed, **LAN 배포됨 — 지금 빌드의 주문 상세는 가린 값을 보여 준다**).

## 1. 바뀐 것
- `GET /orders/lookups/{orderId}` 의 `shippingAddress` 가 **가린 값**이고 `shippingAddressMasked: true` 가 붙는다.
  | 항목 | 상세에 보이는 값 |
  |---|---|
  | 수령인 이름 | 첫·끝 글자(홍*동) |
  | 연락처 | 010-****-1234 |
  | 상세주소 | `***` |
  | 출입 메모·핀(좌표) | 없음(null) |
  | 도로명주소·우편번호·출입 방법 | 그대로 |
- 새 **`POST /orders/lookups/{orderId}/pii-reveals`** `{reason, fields?}` — `reason` 2~200자 필수, `fields` 는 `NAME`·`PHONE`·`ADDRESS`(상세주소+핀)·`ENTRANCE`(출입 메모) 중 고름(없으면 넷 다). 헤더 `Idempotency-Key`·`X-Source-System`. 역할은 상세와 같다(`OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS`).
  - 서버가 audit-service 에 열람 기록을 **먼저 남긴 뒤** 고른 항목만 원문으로 준다(`piiAccessLogId`·`recordedAt`·`fields`·`shippingAddress`).
  - 기록을 못 남기면 **503 — 원문 없음**(`DEPENDENCY_UNAVAILABLE`·`UNKNOWN_RESULT`). 같은 키로 다시 보내면 같은 기록으로 이어진다.
  - 400(사유 짧음·김), 404 `ORDER_NOT_FOUND`·`SHIPPING_COPY_NOT_FOUND`(옛 주문), 403(다른 역할).

## 2. 부탁
- 지금 "보기"(화면 안 토글)를 **사유 입력 → `pii-reveals`** 로 바꿔 주세요. 받은 원문은 화면 메모리에만(저장·로그·URL 금지), 화면을 떠나면 다시 가림.
- 항목별로 열 수 있으면 좋다(예: 전화만 열기 → `fields:["PHONE"]`).
- 확인한 것(PC 1, LAN): 상세 가림, 사유 짧으면 400, 사유 넣고 PHONE 만 열면 원문·기록 ID.

## 3. 아직 정하지 않은 것(정해지면 알림)
- 배송 요청사항(`deliveryNote`)은 지금 가리지 않는다 — 공동현관 번호가 들어갈 수 있어 가릴지 검토 중.
- 열람 기록을 누가 볼 수 있는지(조회 화면), 보존 기간.
