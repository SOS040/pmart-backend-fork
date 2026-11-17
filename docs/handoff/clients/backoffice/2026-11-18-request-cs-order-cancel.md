# [pmart-backend → pmart-clients] 요청: 백오피스 고객센터 "주문 취소" — 상품준비 전 주문 (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: backoffice(`web/backoffice` 고객센터 묶음) — 담당 PC 2
- 근거: 사용자 결정 2026-11-18 "그 추천대로"(고객센터 주문 취소 범위). 흐름 `docs/business/flows/cancel-vs-handover.md` §3 구현 메모(2026-11-18). ADR-0060(상품준비 뒤 고객센터 취소 없음)은 그대로.
- 계약: claim-service **0.4.0** — `POST /claims` description 의 "[2026-11-18 구현, 0.4.0 — 고객센터 주문 취소]", `CreateClaimRequest.identityCheck`·`caseRef`.
- 이어지는 문서: `2026-11-15-clients-request-backoffice-cs-rider-menus.md`(그때 "하지 않는 것: 상품준비 뒤 주문 취소" — 그대로 맞다. 이번 것은 **상품준비 전**).

## 무엇
고객이 전화·채팅으로 "주문 취소해 주세요" 하면, 상담원이 고객 앱 대신 백오피스에서 **주문 전체 취소**를 접수한다.

- 누가: 고객센터 상담원(`CS_AGENT`)·운영 관리자(`OPERATOR_ADMIN`). 그 밖 역할은 403 — 버튼을 숨긴다.
- 언제: 주문 상태 `CONFIRMED`(상품준비 = 첫 피킹 시작 전, 작업자 배정만 된 주문 포함)만. `PREPARING` 부터는 422 `ORDER_NOT_CANCELLABLE` — detail 에 "받으신 뒤 반품으로 안내" 문구.
- 범위: 주문 **전체**(모든 행 × 미이행 수량) — 고객 앱 취소와 같다. 부분 취소 422 `PARTIAL_CANCEL_NOT_ALLOWED`.
- 뒤 흐름: 고객 앱 취소와 같다(현장 중지 → 주문 취소 → 전액 환불). 클레임은 주문 고객 계정에 붙어 고객이 앱 "처리 내역"에서 본다.

## 요청 본문
```json
POST /api/v1/claims   (Idempotency-Key, X-Source-System: backoffice)
{
  "orderId": "...",
  "type": "CANCEL",
  "lines": [{ "orderLineId": "...", "qty": <미이행 수량> }, ...],   // 주문의 모든 행
  "reason": "CHANGE_OF_MIND",            // ClaimReason — 필수, 시스템 사유(FULFILLMENT_REJECTED·OUT_OF_STOCK) 400
  "reasonDetail": "고객 통화 내용 요약",   // 선택(권장)
  "channel": "CS",                       // CS(기본)·OPERATOR. CUSTOMER_APP 400
  "identityCheck": { "method": "ORDER_DETAILS_MATCH" },   // 필수 — ORDER_DETAILS_MATCH·APP_SIGNED_IN_CHAT·OTHER(+ note 필수)
  "caseRef": "CS 접수·사건 ID"             // 선택 — 연결 기록
}
```
- 반품 접수(`type: RETURN`)와 같은 본인 확인 칸·규칙이다 — 반품 화면의 본인 확인 부품을 그대로 쓰면 된다. `faultCode` 는 반품 전용(취소에 넣으면 400).
- 응답 201 = 클레임(`state: STOP_PENDING`). 이미 진행 중인 취소가 있으면 422 `DUPLICATE_CLAIM`(고객이 앱에서 먼저 취소한 경우 등).

## 화면 부탁
1. 주문 상세(고객센터 주문 조회)에 **"주문 취소"** 버튼 — 역할이 맞고 주문이 `CONFIRMED` 일 때만 켬. `PREPARING` 이후면 버튼 대신 "상품 준비가 시작되어 취소할 수 없습니다 — 받으신 뒤 반품으로 안내" 문구.
2. 누르면 확인 창: 취소 사유(필수)·메모, 본인 확인 방법(필수, 기타면 메모 필수), 연결할 CS 접수(있으면 자동). "주문 전체가 취소되고 결제가 전액 환불됩니다" 안내.
3. 버튼을 누른 사이 상태가 바뀌면 422 `ORDER_NOT_CANCELLABLE` 이 올 수 있다 — detail 을 그대로 보이고 주문을 다시 읽는다.
4. 접수 뒤 클레임 상세(기존 반품·클레임 화면)로 이동해 진행 상태를 보이면 좋다.

## 상태
- 백엔드 구현·시험 끝. LAN 배포는 PC 1 일지(`docs/worklog/platform/2026-11-18.md`)에 적는다.
