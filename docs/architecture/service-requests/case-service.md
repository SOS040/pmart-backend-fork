# case-service 에 대한 요청 (MFC·주문 이행 쪽에서)

case-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다. 구현을 시작할 때 아래를 함께 넣는다. 요청한 쪽이 지금 임시로 어떻게 버티는지와, case-service 가 생기면 그쪽에서 바꿀 것을 같이 적는다.

## R-1. ~~결품 "선택 없음" 주문의 CS 이관~~ — 취소됨 (2026-11-08 ADR-0049: 결품은 무조건 부분 취소·환불, 상신 없음)

아래는 기록으로 남긴다. 결품 때문에 case-service 가 할 일은 지금 없다.


- **배경**: 피킹 중 결품(`LineShortageReported`)과, 피킹 시작 때 남아 있는 예약 보류분(같은 사건, reason `BLOCKED`)은 결품 사가(order-orchestration, ADR-0045)가 고객 사전 선택대로 처리한다. 선택이 `품절 상품만 취소`·`전체 취소`면 자동이다. **선택이 없거나 불명이면** 원문(OMS 8.2)은 "출하를 보류해 고객센터로 이관"이다.
- **지금(임시)**: 결품 사가가 `ESCALATED`(사유 `NO_PREFERENCE`)로 멈춘다. PICK 작업은 BLOCKED, 이행부족은 BLOCKED 조각으로 남는다(약속 보존). 운영자 수동 처리 화면(BO-12)도 없다 — 사람이 사가 조회(`GET /internal/sagas?orderId=`)·주문 "이행 확인 필요" 목록(order `GET /fulfillment-issues`)으로 찾는다.
- **case-service 에 필요한 것**
  1. 사건 접수: `LineShortageReported` 중 결품 사가가 상신한 건(또는 orchestration 이 case 에 직접 `POST` — 둘 중 하나로 결정)을 케이스로 연다(`CaseOpened`, 유형 `FULFILLMENT_SHORTAGE`, 주문·주문 줄·SKU·결품 수량·사유·MFC).
  2. 상담원이 고객과 통화한 결과를 기록: `품절 상품만 취소` / `전체 취소`(대체 상품은 원문상 금지 — 선택지에 넣지 않는다).
  3. 결과를 결품 사가에 돌려준다 — 사가가 그 선택으로 이어서 처리(부분 취소·환불 또는 전체 취소). 사가 쪽 입력 계약(예: `POST /internal/sagas/{id}/shortage-decisions`)은 함께 정한다.
  4. 응답 시간 제한(예: 배송 약속 슬롯 시작 − N분까지 결정 없으면) 처리 정책은 **사용자 결정 필요**.
- **case-service 가 생기면 요청한 쪽에서 바꿀 것**: 아래 "MFC·주문 쪽 보충 목록" R-1.

## R-2. 진행 중 주문의 고객센터 취소 (2026-11-08, 사용자: "중도 고객 취소는 아주 예외 — 고객센터를 통해서만 제한적으로 허용")

- **지금**: 고객 자율 취소는 상품 준비 시작(PreparationStarted, 주문 PREPARING) 전까지만(claim `CancelRules` — CONFIRMED). 준비 시작 뒤에는 시스템 취소(결품·작업 거절)만 있고, **고객센터가 고객 요청으로 취소하는 경로는 없다**.
- **case-service 에 필요한 것**: 상담원이 케이스에서 "준비 중 주문 취소"를 요청 → claim 에 운영자 취소(channel CS, 사유·근거 필수, 권한 있는 상담원만) → 기존 S3(현장 중지 → 집품분 취소 복귀 → 주문 취소 → 환불). 허용 조건(어느 단계까지·누가 승인)은 **사용자 결정 필요**.
- **현장 쪽은 준비됨**: 집품 뒤 전체 중지·취소 복귀(IV-08), 인계 허가가 만료됐지만 확인이 없는 포장은 점원 확인까지 중지 PENDING(D-1 안 C). 이미 인계된 포장이 있으면 전체 중지는 거절(회수는 별도).

## MFC·주문 쪽 보충 목록 (case-service 구현 때 같이)
| # | 서비스 | 바꿀 것 |
|---|---|---|
| R-1 | order-orchestration | 결품 사가 `NO_PREFERENCE` 상신 → case 에 케이스 열기 + 케이스 결정 받으면 그 선택으로 계속 |
| R-1 | mfc-fulfillment | 없음(BLOCKED 유지 → 사가 결과로 shortage-resolutions·작업 중지가 온다) |
| R-1 | order | "이행 확인 필요" 에 케이스 ID 연결(선택) |
| R-2 | claim | 운영자(고객센터) 취소 명령 — channel CS, PREPARING 허용, 권한·사유 검사 |
| R-2 | order-orchestration | 없음(기존 S3) |
