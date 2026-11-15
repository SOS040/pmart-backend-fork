# backoffice-bff 에 대한 요청 (주문 이행 쪽에서)

## BO-12. 운영자 수동 개입 화면 (2026-11-09 기록)

- **요청한 곳**: order-orchestration, mfc-fulfillment.
- **대상**
  1. ESCALATED 사가 처리.
  2. 재고가 거절한 인계 재시도(D-8) — API `POST /internal/package-handovers/{id}/retries` 있음.
  3. 중지 실패 재시도 — `POST /internal/stop-requests/{id}/retries` 있음.
- **지금**: 화면 없음 — API·로그로만. backoffice-bff CommandsController 501.
- **사용자 결정(2026-11-16 "너 추천대로")**:
  1. **누가**: `OPERATOR_ADMIN` 만 처리한다(상담원·센터 관리자는 목록을 보지 않음).
  2. **버튼 둘로 시작**: "재시도"(사가 조치 재시도·인계 재시도·중지 재시도)와 "처리 완료로 표시"(사람이 밖에서 해결 — **사유 필수**). 환불은 이 화면에서 하지 않고 기존 환불 절차(claim·payment)로.
  3. **근거**: 재시도·처리 완료 모두 메모 필수. 인계 재시도는 "실물 확인 → 재고 정정 → 재시도" 절차(D-8)를 화면에 안내.
  4. **알림**: 지금은 목록만(멈춘 건 수 표시). case 사건함(R-3)이 생기면 멈춘 건을 사건으로도 연다.
  - 구현은 backoffice 영역(PC 2): bff `/views`(멈춘 건 목록 — orchestration `/internal/sagas?state=ESCALATED`·인계 거절·중지 실패를 모아) + `/commands`(재시도·완료 표시). orchestration `manual-actions`(501)는 order-orchestration(PC 1)에 요청서로.
- 화면은 pmart-clients 몫 — 정해지면 `docs/handoff/clients/` 로 알린다.
