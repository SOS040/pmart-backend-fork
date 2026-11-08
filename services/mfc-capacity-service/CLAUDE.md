# mfc-capacity-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-capacity-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-capacity-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.capacity.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.capacity.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-capacity-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-capacity-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8303.

## 상태
- 구현(슬라이스 4, 2026-10-30): CapacityHold(확보·조회·확정·해제·TTL 만료 정리)와 여력 스냅샷.
  - [시험 정책, 사용자 결정 2026-10-30] 주문 1건 = 수용 단위 1, (MFC, Asia/Seoul 정시 1시간 창)당 수용 단위는 `pmart.capacity.units-per-hour`(env `PMART_CAPACITY_UNITSPERHOUR`, LAN 30). 비우면 Hold·스냅샷 422 CONFIG_REQUIRED.
  - 점유 판정은 `capacity_window` 행 FOR UPDATE 후 활성 Hold 합계(만료 시각 지난 HELD 제외). 잠금 순서 Hold 행 → 창 행. 주문당 활성 Hold 1개(부분 유일 인덱스).
  - 스냅샷 stages 는 WORKFORCE 1개(단계별 환산 전). activeRestrictions = 그 창에 걸린 활성 운영 제한.
- **오전 입고 수령창·하차면(2026-11-08, V8 `unloading_bay_reservation`)**: 설정 `pmart.capacity.receiving.*`(창 시작·끝 Asia/Seoul, 1회 상한, 하루 상한, 하차면 수 — 하나라도 없으면 CONFIG_REQUIRED). 하루 창 하나. 예약 = 창 안 도착 구간 + 박스 환산 단위, 판정 순서 창 밖 → 1회 상한 → 하루 상한 → 하차면 소진(422 `OUTSIDE_RECEIVING_WINDOW`·`PER_ARRIVAL_LIMIT_EXCEEDED`·`RECEIVING_WINDOW_FULL`·`NO_UNLOADING_BAY`). 같은 날 같은 tripId 는 하나(재요청은 같은 예약). 센터+날짜 advisory lock, 빈 `BAY-n` 앞번호부터. 취소는 RESERVED 만(X-Expected-Version). LAN 값 07~10시·150·300·2면은 승인 전 가정값. `ReceivingCapabilityChanged` 는 아직 발행 안 함(값이 설정이라 바뀌는 시점 = 설정 갱신 — 구독자 replenishment·transport-planning 이 생길 때).
- 이벤트(CapacityHoldResult 등) 미발행(AssignmentPublished 만 발행).
- **수동 작업 배정(슬라이스 6b, 2026-11-07, ADR-0043)**: V3 `task_assignment`(작업마다 활성 1건 부분 유일 인덱스, `(task_id, assignment_version)` 유일). `POST /assignments` 그 센터 `MFC_SUPERVISOR` 만, X-Expected-Version = 현재 활성 버전(없으면 0), 같은 작업자 재지정은 200 변화 없음, 바뀌면 앞 배정 SUPERSEDED + `AssignmentPublished`(mfc.capacity.v1, 집계 `MANUAL:<taskId>`). `GET /assignments` 그 센터 운영자·서비스, 기본 ACTIVE. 작업 정본은 fulfillment/inbound — 여기서는 작업 존재·상태를 검사하지 않는다(시작 때 fulfillment 가 판정). 계획(OR-Tools)은 501.
- **작업자·업무·근무(WK-02 입력, 2026-11-08, 계약 0.4.0, V5)**: `/mfcs/{mfcId}/workers`(`WorkerService`·`domain/WorkerRules`·`WorkerStore`) — 등록·업무 변경·상태 변경·근무 등록/취소는 그 센터 MFC_SUPERVISOR, 조회는 그 센터 운영자·서비스. **사용자 결정: 감독자가 등록하고, 업무가 바뀌어도 로그인 정보는 그대로** — `mfc_worker`(열쇠 = workerId = 운영자 ID·표시명·상태·지금 업무 버전)와 `mfc_worker_duty`(버전마다 한 행: 작업 유형·온도대 자격·사유)를 나눴다. 상태 ACTIVE·SUSPENDED·LEFT 는 새 배정만 막고 로그인·센터 권한(auth)은 건드리지 않는다(`mfc_worker_state_change` 이력). 업무·상태 변경은 기존 배정을 지우지 않고 맞지 않는 ACTIVE 배정을 `affectedAssignments` 로 돌려준다. 근무 `mfc_worker_shift`([start, end) 최대 16시간, 겹치면 409 SHIFT_OVERLAP). 배정(`POST /assignments`) 강제 제약: 등록(422 WORKER_NOT_REGISTERED)·ACTIVE(WORKER_NOT_ACTIVE)·업무에 taskType(DUTY_NOT_ALLOWED)·요청 temperatureZone 자격(ZONE_NOT_QUALIFIED). 근무 밖은 막지 않고 `warnings: [OFF_SHIFT]`(앞뒤 하루에 근무가 하나도 없으면 판단 안 함, Q-09 미결). 장비는 아직 없다. 계정 존재 확인은 하지 않는다(auth 호출 없음).
- **배정 추천·확정(WK-09·WK-10, 2026-11-08, 계약 0.5.0, V6)**: `POST /assignment-plans`(`PlanService` → `infrastructure/CpSatPlanner`, OR-Tools CP-SAT Java, 네이티브 처음 쓸 때 로드·동시 2개) — 그 센터 MFC_SUPERVISOR, horizonMinutes 10~480. 입력: mfc-fulfillment `GET /tasks?state=READY,ASSIGNED,IN_PROGRESS`(호출자 토큰 전달, `plan.mfc-fulfillment-base-url`) — READY 가 대상, 나머지와 capacity 활성 배정 작업은 담당의 앞 점유(유형 분만큼 0분부터), ACTIVE 작업자·지금 업무(작업 유형)·근무(근무 등록자는 근무 안에서만). 정책값 `plan.task-minutes.<유형>`·`time-limit`·`packing-stations` 없으면 422 CONFIG_REQUIRED(유형 분만 없으면 그 작업 미배정 CONFIG_REQUIRED). 모델: 작업마다 적격 작업자 선택 구간 하나 또는 미배정, 작업자 NoOverlap, PACK Cumulative ≤ 패킹대, 목적(Q-08 전 임시) 미배정(늦게라도 하는 것보다 늘 비쌈) ≫ 약속 지연 분 ≫ 최대 부하 ≫ 시작 합, seed 고정. OPTIMAL·FEASIBLE = PROPOSED, 그 밖 NO_SOLUTION. 추천은 배정을 안 바꾼다. `POST /assignment-plans/{id}/commits`: PROPOSED 만, 작업 READY·같은 version·활성 배정 없음·작업자 적격·같은 업무 버전 재검사 — 하나라도 어긋나면 STALE(staleReasons) + 409 PLAN_STALE, 아니면 한 트랜잭션에서 항목마다 배정(plan_id) + `AssignmentPublished`(planId, source PLAN, 집계 키는 수동과 같은 `MANUAL:<taskId>` — 작업별 순서). 자동 확정 없음.
- 검토 반영(2026-11-08, 0.5.1, V7, `docs/business/flows/mfc-defaults-status.md`): `GET /assignments` 의 ACTIVE 배정에 `workerEligibility`(조회 때 `WorkerService.rejection` — 정지·퇴사·업무 변경이면 코드, fulfillment 가 시작 거절). 배정 추천 결과에 `settings`(쓴 작업 분·가중치·패킹대·시간 제한, `status: TEMPORARY_IMPLEMENTATION_VALUES`)·`availabilityUnverifiedWorkers`(근무 미등록)·`limitations`. 가중치는 설정 `plan.weights.UNASSIGNED·LATE·MAX_LOAD`(없으면 1e6·1e3·10).
- **운영 제한(BO-08, 2026-11-08, 계약 0.3.0, V4)**: `/operation-restrictions`(`RestrictionService`·`domain/RestrictionRules`·`RestrictionStore`) — 그 센터 MFC_SUPERVISOR, approvalRef 필수(BO-08 승인 ID, 존재 확인 없이 기록). 범위 = 슬롯 창이 [effectiveFrom, effectiveTo) 와 겹치는 것, **시간 경과로 자동 해제 안 함**(DELETE 로만, 여럿이면 모두 적용). INTAKE_STOP → 그 창 신규 Hold 422 OPERATION_RESTRICTED·스냅샷 available 0 + `OperationRestrictionApplied`/`Lifted`. CAPACITY_REDUCE → 그 창 수용 = min(설정, 축소값), 기존 Hold 유지, 지금 점유가 넘는 창마다 `CapacityReduced`(affectedOrders) + 제한의 affectedOrderIds.
