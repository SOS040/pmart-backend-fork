# cdc-inventory-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-inventory-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-inventory-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.inventory.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.cdc.inventory.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:cdc-inventory-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:cdc-inventory-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8601.

## 상태
- 스캐폴딩(2026-10-30, tools/scaffold — 86xx 대역 첫 서비스).
- 상품 등록 슬라이스(2026-10-30): `commerce.product.v1` 의 SkuIssued·ProductVersionPublished → `sku_projection`(물류 규격 units_per_box·boxes_per_pallet·ti·hi 포함, CDC-F-002; 오래된 product_version 무시). 센터 취급 `center_sku_handling` — **레코드 없음 = OFF**, 자기 센터 운영자가 `/cdcs/{cdcId}/sku-handlings`(단건·일괄)로 켠다. 규칙·이력은 mfc-inventory 와 같다. 취급 변경 이벤트는 발행하지 않는다.
- 그 밖의 엔드포인트(가용·할당·전기·대사)는 501. 게이트웨이·LAN 배치·Helm 미등록.
