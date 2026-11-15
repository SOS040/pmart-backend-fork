# [pmart-backend → pmart-clients] 알림: 라이더 찾기·상세·원문 열람, 직원 배송 찾기 — LAN 에 있음 (2026-11-16)

- 보낸 영역: platform(PC 1 — delivery PR #17 을 병합·배포한 쪽)
- 받는 앱: backoffice
- 원 요청: 클라이언트 BR-54(라이더 찾기)·BR-55(도움요청 대기 목록) ↔ 백엔드 `docs/architecture/service-requests/delivery-backoffice-staff-apis.md` BOD-1~6(회신 절에 필드 전부).
- 계약: `rider-service.yaml` **0.4.0**, `delivery-execution-service.yaml` **0.4.0**, `delivery-dispatch-service.yaml` 0.3.2. **2026-11-15 LAN 배포됨** — 알림이 늦었다. 지금 `RiderPages.tsx`·`HelpRequestQueuePage.tsx` 는 목(riderDirectoryMock·helpQueueMock)이라 실서버 모드에서 "서버 API 준비 전" 이 나온다.

## 1. 라이더 찾기·상세 (OPERATOR_ADMIN·RIDER_OPS·CS_AGENT)
- 찾기 `GET /api/v1/riders?riderId=&phone=&restriction=&operatingStatus=&operatingMfcId=&vehicleType=&sort=JOINED_DESC|STATE_CHANGED_DESC&page=&size=`
  - **이름 검색 없음**(사용자 결정 "이름은 동명이인") — 라이더 ID 또는 전화번호 **완전 일치**(숫자만, 하이픈 무시. 부분 번호는 400). 화면의 이름 검색 칸은 뺀다.
  - 응답 `items[]`: riderId·version·`nameMasked`·`phoneMasked`·vehicleType·operatingStatus·newDispatchEnabled·operatingMfcId·restriction·restrictionUntil·stateChangedAt·joinedAt, `hasNext`(page 는 0부터).
- 상세 `GET /api/v1/riders/{riderId}` — 이름·전화는 **가린 값**, `version`(계정 제한의 X-Expected-Version), 배달 지역·운행·설정·제한·마지막 위치 시각. 계좌·거주지는 없다.
- **원문 보기 = 사유 입력 → `POST /api/v1/riders/{riderId}/pii-reveals`** `{reason(2~200자), fields?: [NAME, PHONE]}`(헤더 `Idempotency-Key`·`X-Source-System`) — 주문 원문 열람(ADR-0062)과 같은 방식. audit 기록이 남은 뒤에만 원문(`name`·`phone`), 기록 실패면 503(원문 없음).
- 계정 제한 이력 응답에도 `version` 이 생겼다(BOD-6).

## 2. 직원 배송 찾기 (OPERATOR_ADMIN·CS_AGENT·RIDER_OPS — 모든 센터)
- `GET /api/v1/deliveries?orderId=&riderId=&mfcId=&state=&type=&sort=NEWEST|OLDEST&size=&cursor=` — 주문 상세에서 "배송 보기"(orderId), 라이더 상세에서 "이 라이더의 배송"(riderId). 행은 ID·상태·시각만(개인정보 없음).
- **전달 실패 확정 대기** = `state=FAILURE_REPORTED&sort=OLDEST` — 확정·반려는 이미 있는 `POST /deliveries/{id}/failure-decisions`.
- 배차 상세(제안·배정 라이더)는 `GET /api/v1/deliveries/{id}/dispatch` — 상담원도 조회 가능(취소는 403, ADR-0060).

## 3. 도움요청 — "지원센터 사건"(사건함)으로 보기를 권함
- execution 의 `GET /api/v1/delivery-help-requests`·`POST …/help-requests/{id}/resolutions`(닫기)도 LAN 에 있지만, **2026-11-16 사건함이 생겨** 라이더 도움요청·전달 실패가 사건(RIDER_HELP·DELIVERY_INCIDENT)으로 자동으로 열린다(`backoffice/2026-11-16-request-case-inbox-and-pending-work.md` §1).
- 권함: 도움요청 대기 메뉴는 사건함 목록(`GET /api/v1/cases?kind=RIDER_HELP`)으로 대신하고, 사건의 `refs`(deliveryId·incidentId)로 배송·도움요청 단건(`GET /deliveries/{id}/help-requests/{requestId}` — 라이더가 쓴 내용·사진)을 연다. 처리는 사건 집기·닫기로. execution 의 임시 닫기는 배송 쪽(PC 5)이 사건과 연결한 뒤 정리한다.
