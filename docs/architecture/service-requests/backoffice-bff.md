# backoffice-bff 에 대한 요청 (주문 이행 쪽에서)

## BO-12. 운영자 수동 개입 화면 (2026-11-09 기록)

- **요청한 곳**: order-orchestration, mfc-fulfillment.
- **대상**
  1. ESCALATED 사가 처리.
  2. 재고가 거절한 인계 재시도(D-8) — API `POST /internal/package-handovers/{id}/retries` 있음.
  3. 중지 실패 재시도 — `POST /internal/stop-requests/{id}/retries` 있음.
- **지금**: 화면 없음 — API·로그로만. backoffice-bff CommandsController 501.
- **사용자 결정 필요**: 화면 범위·절차(누가 어떤 근거로 재시도·포기하나).
- 화면은 pmart-clients 몫 — 정해지면 `docs/handoff/` 로 알린다.
