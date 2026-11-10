# delivery-dispatch-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-dispatch-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-dispatch-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.dispatch.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.dispatch.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-dispatch-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-dispatch-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8404.

## 상태
3단계 스캐폴딩(2026-10-23). 업무 로직 미구현 — 모든 엔드포인트는 501 UNSUPPORTED_ACTION.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.

## 이미지 (ADR-0053)
- OR-Tools(라이더 배정, ADR-0050)를 넣으면 **GraalVM Native 로 만들지 않고 JVM 으로 실행한다**. 넣는 커밋에서 ADR-0053 표를 갱신한다.
