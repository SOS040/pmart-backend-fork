# case-service 에 대한 요청 (MFC·주문 이행 쪽에서)

case-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다. 구현을 시작할 때 아래를 함께 넣는다. 요청한 쪽이 지금 임시로 어떻게 버티는지와, case-service 가 생기면 그쪽에서 바꿀 것을 같이 적는다.

## R-1. 결품 "선택 없음" 주문의 CS 이관 (2026-11-08, 사용자 결정 #5)

- **배경**: 피킹 중 결품(`LineShortageReported`)과, 피킹 시작 때 남아 있는 예약 보류분(같은 사건, reason `BLOCKED`)은 결품 사가(order-orchestration, ADR-0045)가 고객 사전 선택대로 처리한다. 선택이 `품절 상품만 취소`·`전체 취소`면 자동이다. **선택이 없거나 불명이면** 원문(OMS 8.2)은 "출하를 보류해 고객센터로 이관"이다.
- **지금(임시)**: 결품 사가가 `ESCALATED`(사유 `NO_PREFERENCE`)로 멈춘다. PICK 작업은 BLOCKED, 이행부족은 BLOCKED 조각으로 남는다(약속 보존). 운영자 수동 처리 화면(BO-12)도 없다 — 사람이 사가 조회(`GET /internal/sagas?orderId=`)·주문 "이행 확인 필요" 목록(order `GET /fulfillment-issues`)으로 찾는다.
- **case-service 에 필요한 것**
  1. 사건 접수: `LineShortageReported` 중 결품 사가가 상신한 건(또는 orchestration 이 case 에 직접 `POST` — 둘 중 하나로 결정)을 케이스로 연다(`CaseOpened`, 유형 `FULFILLMENT_SHORTAGE`, 주문·주문 줄·SKU·결품 수량·사유·MFC).
  2. 상담원이 고객과 통화한 결과를 기록: `품절 상품만 취소` / `전체 취소`(대체 상품은 원문상 금지 — 선택지에 넣지 않는다).
  3. 결과를 결품 사가에 돌려준다 — 사가가 그 선택으로 이어서 처리(부분 취소·환불 또는 전체 취소). 사가 쪽 입력 계약(예: `POST /internal/sagas/{id}/shortage-decisions`)은 함께 정한다.
  4. 응답 시간 제한(예: 배송 약속 슬롯 시작 − N분까지 결정 없으면) 처리 정책은 **사용자 결정 필요**.
- **case-service 가 생기면 요청한 쪽에서 바꿀 것**: 아래 "MFC·주문 쪽 보충 목록" R-1.

## MFC·주문 쪽 보충 목록 (case-service 구현 때 같이)
| # | 서비스 | 바꿀 것 |
|---|---|---|
| R-1 | order-orchestration | 결품 사가 `NO_PREFERENCE` 상신 → case 에 케이스 열기 + 케이스 결정 받으면 그 선택으로 계속 |
| R-1 | mfc-fulfillment | 없음(BLOCKED 유지 → 사가 결과로 shortage-resolutions·작업 중지가 온다) |
| R-1 | order | "이행 확인 필요" 에 케이스 ID 연결(선택) |
