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
- 미구현(501): 배정 계획(OR-Tools), 수령창·하차면. 이벤트(CapacityHoldResult 등) 미발행(AssignmentPublished 만 발행).
- **수동 작업 배정(슬라이스 6b, 2026-11-07, ADR-0043)**: V3 `task_assignment`(작업마다 활성 1건 부분 유일 인덱스, `(task_id, assignment_version)` 유일). `POST /assignments` 그 센터 `MFC_SUPERVISOR` 만, X-Expected-Version = 현재 활성 버전(없으면 0), 같은 작업자 재지정은 200 변화 없음, 바뀌면 앞 배정 SUPERSEDED + `AssignmentPublished`(mfc.capacity.v1, 집계 `MANUAL:<taskId>`). `GET /assignments` 그 센터 운영자·서비스, 기본 ACTIVE. 작업 정본은 fulfillment/inbound — 여기서는 작업 존재·상태를 검사하지 않는다(시작 때 fulfillment 가 판정). 계획(OR-Tools)은 501.
- **운영 제한(BO-08, 2026-11-08, 계약 0.3.0, V4)**: `/operation-restrictions`(`RestrictionService`·`domain/RestrictionRules`·`RestrictionStore`) — 그 센터 MFC_SUPERVISOR, approvalRef 필수(BO-08 승인 ID, 존재 확인 없이 기록). 범위 = 슬롯 창이 [effectiveFrom, effectiveTo) 와 겹치는 것, **시간 경과로 자동 해제 안 함**(DELETE 로만, 여럿이면 모두 적용). INTAKE_STOP → 그 창 신규 Hold 422 OPERATION_RESTRICTED·스냅샷 available 0 + `OperationRestrictionApplied`/`Lifted`. CAPACITY_REDUCE → 그 창 수용 = min(설정, 축소값), 기존 Hold 유지, 지금 점유가 넘는 창마다 `CapacityReduced`(affectedOrders) + 제한의 affectedOrderIds.
