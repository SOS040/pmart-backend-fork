# ADR-0039 승인 반영 회신(ApplicationReceipt)은 이벤트로 — 정본이 반영 트랜잭션에서 outbox 로, approval 이 실행 축을 갱신

- 상태: 승인 (2026-11-06, 사용자 "응" — 추천안(pricing 먼저, 이벤트 방식, 사전 검증은 나중) 진행)
- 보완: ADR-0004·0014(승인 최소 범위 — 실행 축이 `VALIDATING` 에서 멈춰 있던 부분), ADR-0009(Outbox·Inbox), ADR-0035(Stream 소비)
- 관련: `contracts/events/ApplicationReceiptRecorded.v1.json`, `contracts/openapi/approval-service.yaml`(`ApplicationReceipt`·`/approvals/{id}/receipts`)

## 배경
- 승인은 심사 축(review)과 실행 축(execution)이 따로다. 계약은 "결정 후 실행 축은 VALIDATING → 정본의 Receipt 로 갱신"이라고 적었지만 회신이 구현되지 않았다. LAN 승인 87건이 모두 `VALIDATING` 에 머물렀다.
- 2026-11-06 가격 승인 한 건을 pricing-service 가 `PRICE_SCHEDULE_CONFLICT`(같은 SKU 에 더 늦은 예약이 있음)로 거절했다. 그런데 백오피스에는 "승인"만 보여서 사용자가 반영된 줄 알았다. 화면은 실행 축·회신 목록을 이미 그린다. 백엔드가 비어 있었다.
- 계약은 회신 경로로 이벤트와 동기 API(`POST /internal/application-receipts`)를 둘 다 적었다.

## 결정
1. **회신은 이벤트 `ApplicationReceiptRecorded`(v1)**, 토픽 `platform.approval-receipt.v1`.
   - 정본 서비스가 `ApprovalDecided` 를 반영한 **같은 트랜잭션**에서 outbox 에 쓴다. 반영은 됐는데 회신이 빠지는 일이 없다.
   - 승인 토픽 `platform.approval.v1` 과 나눈다. 그 토픽을 구독하는 17개 서비스에 회신이 가지 않게 하려는 것이다.
   - 서비스마다 다른 이벤트를 만들지 않고 하나로 쓴다. 정본의 업무 이벤트(`ProductVersionPublished` 등)는 거절을 담지 못한다.
2. **내용**: `approvalId`, `receiverService`, `receiptSeq`(수신자별 순번, 한 번에 끝나는 반영은 1), `result`(ACCEPTED·APPLIED·PARTIAL·REJECTED·UNKNOWN_RESULT), 선택 `domainRef`·`reason`, `lines[]`(`lineKey`·`result`·`reasonCode`·`domainRef`·`domainVersion`). OpenAPI `ApplicationReceiptRequest` 에 `lines` 를 더했다(필드 추가, 0.2.0).
3. **approval-service**:
   - `application_receipt`(V3)에 저장한다. 같은 `approvalId·receiverService·receiptSeq` 는 한 번만(inbox 외에 한 번 더 막는 키).
   - 실행 축은 **그 승인의 정본(targetService)이 보낸 가장 큰 순번의 회신 결과**로 바꾼다. 전달 축은 `ACKNOWLEDGED`.
   - 다른 수신자의 회신, 반려·심사 중 승인에 온 회신은 기록만 한다. 모르는 승인의 회신은 경고만 남기고 버린다(소비가 막히지 않게).
   - 승인 상세 `receipts`, `GET /approvals/{id}/receipts` 를 채운다.
4. **정본은 승인(APPROVED·PARTIALLY_APPROVED)을 반영했을 때만 회신한다.** 반려된 결정은 반영 요청이 아니다.
5. **1차는 pricing-service 만.** 나머지 승인 대상(product·product-content·center-master·delivery-serviceability·mfc-inbound 등)은 같은 틀로 뒤에 붙인다. 붙이기 전까지 그 종류의 승인은 `VALIDATING` 에 머문다.
6. **동기 API `/internal/application-receipts` 는 지금 만들지 않는다**(501 그대로). 필요해지면 같은 저장 로직에 입구만 더한다.

## 대안
- **동기 회신 API**: 반영을 커밋한 뒤 호출해야 해서, 호출이 실패하면 회신이 사라지거나 재시도 장치를 따로 만들어야 한다. approval 이 멈추면 정본의 소비도 막힐 수 있다.
- **정본별 업무 이벤트를 회신으로 겸용**: 거절·부분 반영을 표현하지 못하고, approval 이 서비스마다 다른 이벤트를 알아야 한다.
- **승인 토픽에 회신을 같이 싣기**: 구독자 17곳이 쓰지 않는 사건을 계속 받는다.

## 결과
- 백오피스 승인 상세에 "승인 / 실행: 거절(PRICE_SCHEDULE_CONFLICT)"처럼 실제 반영 결과가 보인다(화면 변경 없음).
- 이미 있는 87건은 회신이 없었으므로 `VALIDATING` 으로 남는다. 정리는 따로 정한다.
- 남은 일: 다른 정본 서비스의 회신, 상신 단계에서 정본에 미리 검사받기(실행 축 `VALIDATING` 의 원래 뜻), 과거 승인 정리.
