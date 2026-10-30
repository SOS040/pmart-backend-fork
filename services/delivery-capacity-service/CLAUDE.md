# delivery-capacity-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-capacity-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-capacity-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.capacity.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.capacity.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-capacity-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-capacity-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8402.

## 예약 슬롯 (2026-10-31)
- `GET /slots?mfcId&date`: 오늘·내일(Asia/Seoul)만. 운영 구간은 center-master `GET /internal/centers/{id}/calendar?kind=CUSTOMER_ORDER`(서비스 토큰), 그 안에 온전히 들어가는 1시간 시간대. [확정] 00:00~08:00 실제 배송 없음 → 08시 전 시간대는 만들지 않는다.
- 정원 `pmart.capacity.slot-capacity`(정책값, env `PMART_CAPACITY_SLOTCAPACITY`). 비우면 CONFIG_REQUIRED(선택 불가). 센터 없음 404, 시간표 미승인 422.
- 선택 마감 선행시간은 정책이 없어 시간대 시작 시각(`cutoffAt`)에 마감한다 — 정책이 정해지면 바꾼다.
- 점유(held·confirmed)는 슬롯 홀드(자원 확보 사가) 구현 전이라 0. 비회원 토큰도 조회 가능(guest-paths).

## 상태
예약 슬롯 조회 구현(2026-10-31). 슬롯 홀드(`/internal/slot-holds/**`)는 501(자원 확보 단계).
