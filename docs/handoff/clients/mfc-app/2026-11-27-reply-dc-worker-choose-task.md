# 회신: DC 모드 — 작업자가 대기 작업 목록에서 골라 받기

- 보낸 영역: platform (cdc-outbound-service)
- 작성: pmart-backend, 2026-11-27 (PC 1)
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-27-backend-worker-choose-task.md` 의 **DC 부분만**. MFC 부분(mfc-capacity-service)은 mfc 영역(PC 3)이 따로 답한다(사용자 2026-11-27 "mfc 는 mfc가 담당하는데 dc 부분만 하면돼").
- 계약: `contracts/openapi/cdc-outbound-service.yaml` **0.36.0**. LAN 반영은 PC 1 배포 뒤(배포하면 이 줄 아래에 적는다).
- **LAN 반영 2026-11-27**: cdc-outbound `973b37c3b`(pc3, digest `sha256:a0f91ec8…`)·api-gateway 같은 커밋(pc4, `sha256:02346c9a…`)·pmart-config `dc23446`. 게이트웨이 경유 두 경로 모두 cdc-outbound 까지 닿음 확인(센터 권한 없는 토큰 → 서비스 403).

## 1. 대기 목록 — `GET /cdcs/{cdcId}/workers/{workerId}/available-tasks`
- 작업자 본인 토큰만(남의 이름 403).
- 후보 기준과 순서는 [다음 작업 받기]와 **같다**: RELEASED·IN_PROGRESS(보류 아님) Wave 의 담당 없는 CREATED 작업, 우선순위 높은 순 → 기한(dueAt) 이른 순 → 만든 순. **첫 줄 = 지금 next-tasks 가 줄 작업(권장).**
- 거르기 query: `kind`·`zone`·`floor`·`pickMode`·`aisles`(여러 번)·`waveId`·`trfId` — next-tasks 본문과 같은 뜻. `size` 기본 20·최대 100(급한 순 위 N개).
- 응답 `{ items: Task[] }` — 기존 `Task` 모양 그대로. 화면 번호 `taskNumber`·`waveNumber`·`trfNumbers`, 종류 `kind`, 줄 수 = `lines.length`, 첫 상품명 = `lines[0].productName`, 위치 `zone`·`floor`·`aisle`, 기한 `dueAt`.
- 보기만 한다(잠그지 않음). 들고 있는 작업은 이 목록에 없다 — `GET /cdcs/{cdcId}/tasks?assigneeId=` 로.

## 2. 고른 작업 맡기 — `POST /cdcs/{cdcId}/workers/{workerId}/task-claims`
- 본문 `{ "taskId": "…" }`, 헤더 `Idempotency-Key`·`X-Source-System` 필수(next-tasks 와 같음).
- 201 = 새로 배정(state ACCEPTED — 받기가 곧 수락). 200 = 이미 그 작업을 들고 있음(그대로 돌려줌).
- 409 `ALREADY_HOLDING`(다른 작업을 들고 있음 — 한 번에 하나), `TASK_TAKEN`(그새 다른 사람이 맡음 — 목록을 다시 불러 주세요), `NOT_AVAILABLE`(담당은 없지만 Wave 보류·취소·끝남 등), `IDEMPOTENCY_CONFLICT`. 없는·다른 센터 작업 404, 남의 이름 403.
- DC 에는 자격·근무 검사가 아직 없어 422 `WORK_AREA_NOT_ALLOWED` 같은 코드는 없다(next-tasks 와 같음).

## 3. 정해 달라던 것 (DC)
- 전부 vs 위 N개: **급한 순 위 N개**(`size`, 기본 20). 출하 기한을 지키려면 위쪽부터 보이는 게 낫다.
- "그 사람만" 받는 작업: DC 에는 아직 없다(MFC 의 자기 PACK·격리 이동 같은 규칙 없음). 생기면 next-tasks 와 같이 목록에서 뺀다.
- [다음 작업 받기]: **그대로 둔다.** 목록 첫 줄과 같은 작업이라 "권장" 표시와 맞는다.
- 잘못된 Wave 작업을 집는 일(DC 시뮬 K20543)이 사람이 고르는 길로도 생길 수 있다 — 목록 줄에 Wave·TRF 화면 번호를 꼭 보여 주세요.

## 4. 시험
- `OutboundFlowIT` 5단계 — 목록 2개(첫 줄 = next-tasks 결과), size=1, 없는 Wave 거르기 0개, 남의 이름 403, TASK_TAKEN·ALREADY_HOLDING·404·403, 맡기 201·같은 작업 200, 다 맡은 뒤 빈 목록. cdc-outbound `check`+`integrationTest` 22개 통과(2026-11-27).
