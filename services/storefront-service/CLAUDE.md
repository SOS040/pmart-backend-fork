# storefront-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `storefront-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/storefront-service.yaml` → `openApiGenerate` 가 `com.pmart.storefront.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.storefront.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:storefront-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:storefront-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8106.

## 기본 진열 (2026-10-30)
- 진열 편성 승인(BO-05, `/storefront/layout-publications`)이 아직 없어 **기본 진열(layoutVersion 0)** 만 있다: HOME 탭 하나, 섹션 `S-ALL`("전체 상품", 최근 게시 순, 첫 묶음 10개). 배너 없음, RANKING·NEW 탭 404 `TAB_NOT_CONFIGURED`. 편성 버전이 생기면 이것을 대체한다.
- 투영(V2): `display_product`(상품 버전·대표 SKU·판매 상태·이름·이미지·처음 게시 시각), `display_sku_price`. 구독 commerce.product.v1·commerce.pricing.v1, InboxGuard + 버전·시각 비교(옛 사건 무시). 가격은 조회 때 대표 SKU 로 붙인다.
- 콘텐츠 사건에는 이름·이미지가 없어 product-content 공개 카드 API(`pmart.storefront.content-base-url`)로 보강한다(ADR-0021).
- 진열 대상: 게시 콘텐츠·상품 버전이 모두 있고 판매중지가 아닌 상품. carried 는 null(MFC 취급 사건 없음).

## 상태
기본 진열 홈·섹션 다음 묶음·배너(빈 목록) 구현(2026-10-30). 편성 게시·내부 발효 버전 조회는 501.
