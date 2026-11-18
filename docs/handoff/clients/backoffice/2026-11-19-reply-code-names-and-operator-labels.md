# 회신 — 화면에 코드 그대로 남은 값(C1~C7)·운영자 ID → 이름·사번(R1·R2)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: pmart-clients `docs/handoff/backoffice/2026-11-18-backend-code-names.md`, `docs/handoff/backoffice/2026-11-18-backend-operator-display-names.md`
- 사용자 결정(2026-11-19): "1번 너 추천대로 해주고 2번도 너가 말한대로 해줘"

## A. 코드 이름 (code-names)

platform 몫 C4~C7 은 계약 설명에 값 목록과 뜻을 적었다(설명만 — 값·모양 변경 없음). mfc 몫 C1~C3 은 PC 3 에 넘겼다.

| # | 칸 | 계약 | 값 → 뜻 |
|---|---|---|---|
| C4 | 이행 확인 필요 사유 `FulfillmentIssue.reason` | order-service **0.13.1** | `QUALITY_HOLD` 품질 격리 · `COUNT_LOSS` 실사 손실 · `SHORT` 피킹 결품(선반에 없음) · `DAMAGED` 피킹 중 파손 · `BLOCKED` 피킹 차단(꺼낼 수 없음) · `UNKNOWN` 사건에 사유 없음. 목록 밖 값이 오면 코드 그대로 |
| C5 | 멈춘 주문 오류 코드 `lastErrorCode` | order-orchestration-service **0.4.1** | **열린 값.** ① 하위 서비스가 거절한 코드 그대로(그 서비스 계약의 오류 코드 — 예 `INSUFFICIENT_STOCK`·`VERSION_CONFLICT`) ② 사가가 붙이는 코드: `UNKNOWN_RESULT` 결과 모름 · `PAYMENT_FAILED` 결제 미승인 · `AMOUNT_MISMATCH` 결제 금액 다름 · `RESOURCE_EXPIRED` 결제 가능 시간 지남 · `RESERVATION_LOST` 확보한 재고·용량 사라짐 · `RESERVATION_UNKNOWN` 결품 사건에 예약 ID 없음 · `CONFIG_REQUIRED` 설정 없음 · `DELIVERY_NOT_FOUND`·`ORDER_UNKNOWN`·`ORDER_CANCELLED`·`NOTHING_TO_RESEND`(오배송 재발송). 설명은 늘 `lastErrorMessage` 에 있으니 이름표가 없으면 그 글을 같이 보여 주면 된다. 목에서 쓴 `PAYMENT_UNKNOWN` 은 지금 서버가 내지 않는다 |
| C6 | 접수 건 우선순위 `Priority` | case-service **0.24.2** | `P1` 긴급 · `P2` 높음 · `P3` 보통 · `P4` 낮음. 지금 서버가 정하는 값은 P1(사고·안전 위협)과 P2(그 밖 자동 생성 건 전부)뿐 — P3·P4 는 나오지 않는다. **접수 건 목록 정렬에 쓴다**(P1 먼저 → 처리 기한 → 생성 순). 처리 기한(slaDueAt)은 우선순위가 아니라 종류별 규칙 |
| C7 | 승인 유형 `SUPPLY_BUSINESS` | approval-service **0.4.1** | SCM 공급 심사(BackOffice BO-18) — 센터 간 공급 요청(TRF)의 승인·부분 승인·반려. 지금 이 유형으로 만들어지는 요청은 없다(SCM 공급 흐름 미구현) — 이름표 "공급 심사" |
| C1~C3 | MFC 중지 실패 사유·막힌 사유·인계 거절 사유 | mfc-fulfillment | **PC 3 에 요청**(`docs/architecture/service-requests/mfc-fulfillment-service.md` F-11). 회신 오면 이 폴더에 알림 |

## B. 운영자 ID → 이름·사번 (operator-display-names)

### R1 — 만듦: hr-service **0.8.0** `GET /api/v1/hr/employee-labels`
- `?operatorIds=OP-a&operatorIds=OP-b` (1~50개, 같은 값은 한 번만). 형식 틀림·51개 이상은 400.
- 응답 `{ items: [ { operatorId, name, employeeNo } ] }` — **필드 이름은 `employeeNo`**(요청서의 employeeNumber 대신, 직원 조회 `Employee.employeeNo` 와 같게). 순서 없음.
- 누가: **직원 토큰이면 누구나**(역할 제한 없음). 이름은 **가리지 않는다**(직원끼리 쓰는 화면 — 인사팀 앱 권한 상세의 승인자도 실명).
- 퇴사자도 돌려준다(지난 기록 표시용). 모르는 ID 는 items 에서 빠진다 → 화면 "확인 불가"(없음과 구분 — 요청서대로).
- 이름·사번 말고는 싣지 않는다(메일·소속·연락처·재직 상태 없음).
- 게이트웨이: 라우트는 `/api/v1/hr/**` 그대로, 허용 목록 GET 한 줄. 목 모드는 위 모양으로 만들면 된다.
- **LAN**: 배포 전(사용자 배포 결정 뒤 이 줄을 고친다).

### R2 — PC 2 에 요청(선택): bff 화면 응답에 같은 표시
- `docs/architecture/service-requests/backoffice-bff.md` BF-1 — 승인함·처리 대기·접수 건함의 운영자 ID 옆에 `…Label`. 모양은 PC 2 가 정해 알린다. 그 전까지는 화면이 R1 을 직접 부르면 된다(한 화면 한 번, 50개씩).
