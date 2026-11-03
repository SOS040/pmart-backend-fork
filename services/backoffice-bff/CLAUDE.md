# backoffice-bff

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `backoffice-bff` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/backoffice-bff.yaml` → `openApiGenerate` 가 `com.pmart.backoffice.bff.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.backoffice.bff.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:backoffice-bff:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:backoffice-bff:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8504.

## 승인함 (2026-11-03, BR-40)
- `GET /views/approval-inbox`(`application/ApprovalInboxView`): approval-service `GET /api/v1/approvals` 를 **운영자 본인 토큰**으로 읽어(`infrastructure/ApprovalClient`, ADR-0026 의 서비스 간 인증이 아니라 사용자 토큰 전달) 화면 항목으로 묶는다. `dossierId` = `approvalId`. 결정은 approval-service 상세에서.
- approval-service 장애(연결 실패·5xx)면 200 + 빈 items + `unavailableSources=[approval-service]`(추정하지 않음). 4xx 는 같은 뜻(401·403 → 403, 그 밖 400). 주소 `pmart.bff.approval-base-url`(기본 `http://approval-service:8505`, local 은 pmart-config).

## 상태
3단계 스캐폴딩(2026-10-23). 승인함 구현(2026-11-03). 그 밖의 `/views/*`·`/commands/*` 는 501.
