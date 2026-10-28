# approval-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `approval-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/approval-service.yaml` → `openApiGenerate` 가 `com.pmart.approval.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.approval.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:approval-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:approval-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8505.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- 슬라이스 1 최소 범위(2026-10-28, ADR-0014): 변경 요청 생성·조회, 제출(승인 단위 확정·이중 발급 금지 `DUPLICATE_APPROVAL`), 승인함·상세, 결정(APPROVE/REJECT) → `ApprovalDecided`(`platform.approval.v1`). 저장은 JDBC(`ApprovalStore`, 본문 jsonb 스냅샷), 명령은 `RequestIdempotency` 안에서.
- 승인자 권한: `pmart.approval.approver-roles.<종류>=<역할>` 설정값 + 자기승인 금지. 미설정 종류는 `CONFIG_REQUIRED`. auth-service 행위 시점 검증(대행·센터 범위)은 auth 구현 후 `ActionAuthorizer` 로 교체.
- 미구현(501): 철회, 부분 승인·증빙 요청, 재심사, 반영 회신(`/internal/application-receipts`), 일괄 변경, 정책 참조.
