# [pmart-backend → pmart-clients] BR-05 추가 질문 회신: 작업 시작의 503 처리

- 회신 대상: `pmart-clients/docs/handoff/clients/2026-10-24-backend-br05-start-503.md`
- 계약 반영: `contracts/openapi/mfc-fulfillment-service.yaml` `startTask`, `getTaskStart` (이 회신과 같은 커밋)
- 지적이 맞다. 503 이 공통 응답(`DEPENDENCY_UNAVAILABLE / UNKNOWN_RESULT`)을 참조하고 있어 의미가 모호했다. 작업 시작 전용 503 으로 바꿨다.

## 판정 순서 (계약에 명시)
담당 조회(mfc-capacity) → 판정 기록(STARTED / REJECTED) → 응답.
담당 조회가 실패하면 아무것도 기록하지 않는다.

## 답변
1. **503 의 의미**: 작업 시작의 503 은 항상 `code: DEPENDENCY_UNAVAILABLE` 이고 **시작 안 됨이 확정**이다. 이 명령은 `UNKNOWN_RESULT` 를 반환하지 않는다. 결과 불명은 응답을 받지 못한 경우(타임아웃·연결 끊김)뿐이다.
2. **짝 조회**: `GET /tasks/{taskId}/starts/{requestId}` 는 기록된 판정만 보여준다. 503 으로 끝난 요청은 404 다.
   다만 404 는 서버에 도달하지 않은 요청과 아직 처리 중인 요청에서도 나온다. **404 만으로 시작 안 됨을 확정하지 않는다.**
3. **같은 키 재전송**: 503 은 기록되지 않으므로 같은 `Idempotency-Key` 로 다시 보내면 처음부터 다시 판정한다. 새 키는 필요 없다.
   - 같은 키의 앞선 요청이 아직 처리 중이면 409 `IDEMPOTENCY_CONFLICT`(`retryable=true`)다. 잠시 뒤 같은 키로 다시 보낸다.
   - 같은 키에 다른 본문이면 409 `IDEMPOTENCY_CONFLICT`(`retryable=false`)다.

## 클라이언트 처리안 검토
제안하신 처리안은 대체로 맞다. 한 가지만 바꾼다.

| 상황 | 처리 |
|---|---|
| 503 `DEPENDENCY_UNAVAILABLE` | 시작 안 됨. 같은 키로 재시도 버튼 (제안대로) |
| 응답 없음 (타임아웃·연결 끊김) | "확인 중" 표시 후 **같은 키로 재전송**해 확정한다. 짝 조회와 `activeStart` 는 보조 수단이다 |
| 짝 조회 404 | 시작 안 됨으로 확정하지 않는다. 같은 키로 재전송한다 |
| 409 `IDEMPOTENCY_CONFLICT` + `retryable=true` | "확인 중" 유지, 잠시 뒤 같은 키로 재전송 |

`UNKNOWN_RESULT` 는 이 명령에서 오지 않지만, 방어적으로 "확인 중"으로 처리해 두는 것은 문제없다.
확정 전에 피킹 화면으로 넘어가지 않는 원칙은 그대로 유지한다.

## 검증 상태
redocly lint 통과. 서비스 빌드: 2026-10-24 재실행, `3916354` 기준: `:services:mfc-fulfillment-service:check`, `:services:delivery-dispatch-service:check` **통과** (단위·통합 테스트 각 1건, 아직 골격 테스트만 있음).
