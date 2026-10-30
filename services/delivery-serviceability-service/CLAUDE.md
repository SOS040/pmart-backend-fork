# delivery-serviceability-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-serviceability-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-serviceability-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.serviceability.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.serviceability.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-serviceability-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-serviceability-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8401.

## 권역 매핑·판정 (2026-10-30)
- 권역 = GeoJSON Polygon/MultiPolygon([경도, 위도]), `ZoneMatcher` 레이 캐스팅. 중첩은 우선순위가 가장 큰 권역이 하나일 때만, 아니면 OVERLAP(임의 선택 금지).
- 매핑 버전: `POST /serviceability-versions`(운영자 초안, 내용 해시) → approval SERVICEABILITY(역할 `SERVICEABILITY_APPROVER`) → ApprovalDecided → 시험 주소 검증 → ACTIVE + `ServiceabilityVersionApplied`(토픽 `delivery.serviceability.v1` 명시). 해시 불일치·시험 주소 불일치는 REJECTED.
- 판정은 좌표로만(지오코딩 없음). 좌표 없으면 NEEDS_INPUT(geo), 발효 버전 없으면 CONFIG_REQUIRED.

## 상태
권역 매핑 버전·좌표 판정 구현(2026-10-30). 배송 가능 조건(`/delivery-conditions`)은 501(주문서 단계에서).
