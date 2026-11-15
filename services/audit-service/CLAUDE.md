# audit-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `audit-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/audit-service.yaml` → `openApiGenerate` 가 `com.pmart.audit.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.audit.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:audit-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:audit-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8503.

## 상태
3단계 스캐폴딩(2026-10-23). 감사 사건(`/internal/audit-entries`·`/audit-entries`)은 아직 501 UNSUPPORTED_ACTION.

- **개인정보 열람 기록(2026-11-15, BO-25 — 계약 0.2.0, V2)**: `POST /internal/pii-access-logs`(기록)·`GET /internal/pii-access-logs/{id}`(짝 조회) — `InternalController` → `application.PiiAccessLogService` → `infrastructure.PiiAccessLogStore`(JDBC, 표 `pii_access_log`).
  - 첫 호출자: order-service 직원 주문 배송지 원문 열람(`POST /orders/lookups/{orderId}/pii-reveals`). 호출자는 기록이 된 뒤에만 원문을 준다(실패면 원문 없음).
  - 멱등: `RequestIdempotency`(X-Source-System, Idempotency-Key). 본문 비교에서 occurredAt·traceId 는 뺀다(`PiiAccess.forIdempotency`) — 재시도마다 시각이 달라도 같은 기록(200), 다른 사유·항목은 409. 처음은 201.
  - 추가 전용: 수정·삭제 API 없음 + DB 트리거 `trg_pii_access_log_append_only` 가 UPDATE·DELETE 를 거부. **보존 2년 뒤 파기**(ADR-0062 결정 9, V3): `application.PiiAccessLogPurger`(기본 6시간마다, 설정 `pmart.audit.pii-retention`=P2Y·`pii-purge-interval`) — 트랜잭션마다 `pmart.audit_purge=on` 을 켠 DELETE 만 트리거가 허용(UPDATE 는 언제나 거부). 지운 수만 로그.
  - 원문 개인정보는 받지 않는다(subjectRef = 계정 ID, fields = 항목 코드). 열람 사유(purpose)는 저장하되 로그 금지(`PiiAccess.toString` 이 숨김).
  - 직원용 열람 기록 조회 화면 API(누가 누구 기록을 볼 수 있나)는 아직 없다 — 결정 필요.
