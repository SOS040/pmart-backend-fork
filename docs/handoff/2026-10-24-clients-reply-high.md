# [pmart-backend → pmart-clients] 계약 공백 요청 회신 (우선순위 높음 6건)

- 회신 대상: `pmart-clients/docs/handoff/2026-10-24-backend-requests-high.md`
- 계약 반영 커밋: pmart-backend `d3ce55c` (2026-10-24, main push 완료)
- 요약: 수용 1 (BR-05), 부분 수용 1 (BR-06), 보류 4 (BR-01·02·03·07)
- 반영한 경로도 서버 구현 전까지는 501을 반환한다. 연동 테스트는 구현 완료 공지 후에 한다.
- 이 회신에 대한 추가 질문은 `pmart-clients/docs/handoff/` 에 새 파일로 남긴다. 항목 번호(BR-xx)는 그대로 쓴다.

---

## BR-05 MFC 작업 목록·작업 상세 — 수용 (`d3ce55c`, mfc-fulfillment-service.yaml)
| 경로 | 내용 |
|---|---|
| `GET /tasks?mfcId=&assignee=me&type=&state=` | 내 작업 목록. state 생략 시 ASSIGNED, IN_PROGRESS, BLOCKED. 응답 `asOf`는 담당자 정보의 기준시각 |
| `GET /tasks/{taskId}` | 작업 상세. `lines[]`는 taskLineId, orderLineId, sku, barcode, locationId, lotId, expiresOn, plannedQty, completedQty, cancelledQty, remainingQty. 그 외 `assignment.assignmentVersion`, `activeStart` |
| `POST /tasks/{taskId}/starts` | 작업 시작(WK-03 한 요청만 성공). 본문 `workerId`, `assignmentVersion`, `deviceId`. 헤더 Idempotency-Key, X-Source-System, X-Expected-Version |
| `GET /tasks/{taskId}/starts/{requestId}` | 시작 판정 조회(requestId = Idempotency-Key). outcome STARTED / REJECTED / UNKNOWN |

시작 거절 코드(409): `ALREADY_STARTED`, `NOT_ASSIGNEE`, `ASSIGNMENT_VERSION_MISMATCH`, `PREDECESSOR_NOT_COMPLETED`, `INVALID_STATE`, `VERSION_CONFLICT`

**클라이언트 참고**
- 시작 응답을 받지 못하면 같은 키로 재전송하거나 짝 조회로 확정한다. 새 키로 다시 시작하지 않는다.
- 완료 표시는 실행 결과가 APPLIED가 된 뒤에만 한다(기존 `/tasks/{taskId}/executions`).
- 미정 사항:
  - 시작 점유 만료(MFC Q-09): 지금 계약에서는 만료되지 않는다.
  - 목록 우선순위·동선 순서: 현재는 생성시각 오름차순이다.
  - `assignee=me`는 작업자 로그인 방식(BR-02)이 정해져야 실제로 동작한다.

## BR-06 라이더 배차 제안 수신 — 부분 수용 (`d3ce55c`, delivery-dispatch-service.yaml)
**수용**
- `GET /offers?state=`
  - 라이더 본인의 제안만 반환한다. state 생략 시 SENT이고 만료 전 제안만 나온다.
  - 응답 `serverTime`과 각 Offer의 `remainingSec`(서버 기준 남은 응답 시간, 초)이 추가됐다.
- 요청 예시 경로 `/riders/me/offers` 대신 `/offers`로 정했다. 게이트웨이에서 `/riders/**`가 rider-service로 라우팅되기 때문이다.
- 푸시를 받았든 못 받았든 이 조회가 offerId를 얻는 기준 경로다. 알림 유실이나 앱 재실행 때도 이 조회로 복구한다.
- 남은 시간은 단말 시계가 아니라 `remainingSec` 또는 `expiresAt - serverTime`으로 표시한다. 수락 판정도 서버 수신 시각 기준이다.

**보류**
- 푸시·SSE 전달 채널: notification 채널이 `[결정 필요]` 상태다. 결정 전에는 폴링을 쓴다. 폴링 간격은 계약으로 정하지 않았다.
- 일반배차모드 대기 목록: 공개 범위와, 목록에서 고르는 것이 곧 수락인지가 업무 문서에 없다.
- 푸시 토큰 등록(BR-09)은 중간 우선순위 묶음에서 따로 회신한다.

## BR-01 고객 결제 — 보류 (설계안 승인 대기)
업무 문서에 확정된 것은 두 가지다. 결제창 복귀만으로 결제 완료를 표시하지 않는다(클라이언트 원칙과 같다). 승인은 서버가 한다.
진입 정보 전달, 인증 결과 전달 경로, success/fail URL을 정하는 주체, 모바일 복귀 방식은 문서에 없다.
참고로 `POST /order-drafts/{draftId}/checkouts`는 order-orchestration이 아니라 **order-service** 계약이다.

백엔드가 제안한 설계안(승인 대기, 바뀔 수 있음)
1. `GET /order-drafts/{draftId}`가 `nextAction=PAY`일 때 `checkout`(clientKey, pgOrderId, amount, orderName, expiresAt 등)을 포함한다.
2. 인증 결과(paymentKey, pgOrderId, amount)는 payment-service 고객용 명령으로 전달한다. 서버가 토스 승인을 호출한다. 최종 결과는 주문 초안·주문 조회로 확정한다.
3. successUrl과 failUrl은 서버 설정으로 고정한다. 클라이언트는 이 값을 넘기지 않는다.
4. 모바일 복귀 방식(앱 딥링크 스킴 / 서버 URL을 거쳐 앱으로)은 결정 대기 중이다.

목 구현은 위 형태를 가정해도 되지만, 필드 이름은 계약 반영 후 확정된다.

## BR-02 고객 외 주체 인증 — 보류 (팀 결정 대기)
- 라이더: 업무 문서에는 화면 흐름(전화 인증 → 아이디·비밀번호 가입)만 있다. OTP 방식·업체와 로그인 API는 없다. 본인확인을 auth와 rider 중 어디서 하는지도 정해지지 않았다.
- MFC 점원: 직원 ID 바코드는 "작업 이력 기록" 용도로만 정의돼 있다. 로그인 방식과 공용 PDA 교대 로그인은 미정이다(MFC Q-02).
- 운영자: IAM 연동·세션·추가 인증은 BO-Q01 미결이다.
- 간선기사: fleet-service는 2단계이고 단계 계획 자체가 `[제안]` 상태라 일정을 아직 줄 수 없다.

## BR-03 토큰 보관 방식 — 보류 (팀 결정 대기)
- 쿠키/CSRF는 `[팀 확인 필요]`다. 사용자 토큰 수명 수치도 정해지지 않았다.
- 현재 계약(`POST /auth/tokens`)은 access·refresh 토큰을 **응답 본문**으로 내려준다. 클라이언트 임시안(모바일은 보안 저장소, 웹 access token은 메모리)은 현재 계약과 맞으므로 그대로 진행해도 된다.
- 남은 결정은 웹 refresh 토큰을 HttpOnly 쿠키로 할지 하나다. 쿠키로 정해지면 게이트웨이 쿠키 필터와 CSRF 처리를 같이 바꾸고 다시 공지한다.

## BR-07 파일 업로드 — 보류 (팀 결정 대기)
- `photoRefs` 등의 설명에 나오는 file-service는 서비스 목록과 계약에 없다. 오브젝트 스토리지도 기술 스택에 없다.
- 크기·형식·개수·보존 기간·참조 만료는 업무 문서에서 모두 미확정이다.
- 업무 문서에 확정된 원칙은 두 가지다. 업로드 성공과 업무 명령 성공은 별개다. 사진에 다른 고객·신분증·출입 비밀번호가 노출되면 안 된다.
- **주의:** review-service의 `photoIds maxItems: 5`는 업무 근거가 없는 값이다. 확정 규칙으로 화면에 고정하지 않는다.

---

## 백엔드 측 남은 일
- BR-01 설계안이 승인되면 계약에 반영하고 커밋 SHA를 회신
- BR-02·03·07 팀 결정 후 반영
- 중간·낮음 6건(BR-04, BR-08~12)은 별도로 회신
