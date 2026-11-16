# 요청 — 상담원 전화·채팅 접수 화면 (case-service 0.12.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(고객센터 — 상담원·관리자)
상태: case PR 대기(병합·배포는 PC 1).

> 화면 용어(2026-11-16 사용자 결정, pmart-clients PR #28): 사건(case) = **"접수 건"**, 의무 = 후속 처리. 아래 API 이름은 백엔드 이름 그대로.

## 1. 정한 것 (사용자 2026-11-16 "그래 일단 그대로 진행해" — BO-11)
- 상담원이 **전화·채팅·메일**로 받은 고객 연락을 남긴다. 전화 시스템 연동은 없다 — 경로는 고르는 값이다.
- **접수는 명령이 아니다.** 취소·환불은 이 화면에서 하지 않는다(클레임·주문 메뉴에서). 고객이 동의한 것(결품 대체 등)은 **기록만** 한다.
- **회원만**: 고객 계정(accountId)이 있어야 한다. 비회원 연락은 받지 않는다.
- **본인 확인은 필수로 고른다.** 어떻게 확인했는지만 남기고, 고객이 말한 번호·주소는 적지 않는다.
  - 주문 내용 대조 `ORDER_DETAILS_MATCH` — 주문 번호를 받아 화면의 주문(품목·시각·금액)과 맞춰 봄. **주문 번호 필수.**
  - 앱 로그인 채팅 `APP_SIGNED_IN_CHAT` — 앱에 로그인한 채팅. **채팅일 때만** 고를 수 있다.
  - 기타 `OTHER` — **어떻게 확인했는지 메모 필수**(200자).
- **대리(가족 등)**: 받을 수는 있고 "대리"로 표시된다. 서버가 취소·환불을 막지는 않는다(대리 정책이 아직 없음). 그래서 **화면이 경고**한다: "본인 확인 전에는 취소·환불을 요청하지 마세요."
- 새 연락이면 **상담원이 남긴 고객 문의**가 하나 생긴다(고객센터 문의 목록에 같이). 이 문의는 **고객 앱에는 보이지 않고, 답변을 달아도 고객 알림이 가지 않는다**(답변은 통화·채팅 기록 용도).
- 이미 있는 접수 건(사건함 건이나 고객 문의 건)에 대한 연락이면 **그 건에 연결**한다 — 새 건은 생기지 않는다.

## 2. API
- 남기기 `POST /api/v1/cs-intakes` (Idempotency-Key·X-Source-System)
  ```json
  {
    "channel": "PHONE",            // PHONE | CHAT | EMAIL
    "category": "DELIVERY",        // ORDER DELIVERY PAYMENT REFUND PRODUCT ACCOUNT OTHER
    "customerRef": "<고객 accountId>",
    "subjectRef": "<주문 ID — 선택>",
    "summary": "배송이 늦는다는 전화",   // 2000자
    "identityCheck": { "method": "ORDER_DETAILS_MATCH", "note": null },
    "callerRelation": "SELF",      // SELF 본인 | PROXY 대리
    "consent": { "consentType": "OOS_SUBSTITUTE", "given": true, "detail": "우유 1개 대체 동의" },  // 선택
    "linkCaseId": "<기존 접수 건 ID — 선택>"
  }
  ```
  → 201 `Intake` — `intakeId`, `caseId`(새 문의 건 또는 연결한 건), `inquiryId`(새 문의, 연결이면 null), `linked`, `callerRelation`, `identityCheck`, `consent`, `receivedBy`, `receivedAt`.
  - 400: 경로가 APP 등 · 분류 RIDER(→ 라이더 컴플레인 기록 화면으로 안내) · 주문 내용 대조인데 주문 없음 · 전화인데 앱 로그인 채팅 · 기타인데 메모 없음 · 고객 계정 없음.
  - 403: 상담원·관리자가 아님(또는 주문을 볼 수 없는 역할). 404: 연결할 건이 없거나 볼 수 없음.
  - 422 `ORDER_NOT_FOUND`(없는 주문) · `CUSTOMER_ORDER_MISMATCH`(**그 고객의 주문이 아님** — 계정·주문 번호를 다시 확인하라고 안내). 503: 주문 서비스 응답 없음.
- 목록 `GET /api/v1/cs-intakes?caseId=|customerRef=|subjectRef=`(하나 이상 필수, 최근 순, size·cursor) — 접수 건 상세에 "이 건의 연락 기록", 고객·주문 화면에 "이 고객/주문의 연락 기록".
- 단건 `GET /api/v1/cs-intakes/{intakeId}`.
- 고객 문의 목록·상세(`/inquiries`)에 `origin: "STAFF_INTAKE"`, `channel`, `callerRelation` 이 붙는다 — **`callerRelation: "PROXY"` 면 대리 표시와 경고**.

## 3. 화면 제안
- 고객센터 메뉴에 **"전화·채팅 접수"**: 경로 → 고객 계정 → 주문(선택, 통합 조회에서 고른 주문을 넘겨받으면 좋음) → 본인 확인 방법(필수, 라디오) → 본인/대리 → 내용 → 동의(접어 두기) → 기존 건 연결(선택).
- 대리를 고르면 입력 칸 위에 노란 경고. 기타를 고르면 메모 칸이 필수.
- 주문 내용 대조를 고를 때 **주문 화면(가린 상세)을 옆에 띄워** 맞춰 보게 하면 좋다 — 배송지 원문 열람(pii-reveals)은 본인 확인 용도로 쓰지 않는다(열람 기록이 남음).
- 문의 목록에 경로 아이콘(전화·채팅·메일)과 "대리" 표시.

## 4. 나중(지금은 없음)
- 등록 번호로 다시 걸기·앱으로 확인 요청·외부 본인인증 — 새 기능과 다른 PC 작업이 필요해 대리 정책과 함께 정한다.
