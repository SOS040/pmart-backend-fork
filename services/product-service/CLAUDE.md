# product-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `product-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/product-service.yaml` → `openApiGenerate` 가 `com.pmart.product.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.product.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:product-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:product-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8101.

## 명령 (2026-10-30)
- 사건 토픽은 모두 `commerce.product.v1`(`ApprovalApplier.PRODUCT_TOPIC`). `SkuIssued` 는 집합 이름(Sku) 기본 토픽이 아니므로 명시해서 발행한다.
- 판매 상태(`/products/{id}/sale-state-changes`): 운영자 역할 `pmart.product.sale-state-change-role`(기본 `MD`)만. 일시품절·판매 재개는 바로, **판매중지는 PRODUCT 승인 필요**(사용자 결정). 승인 요청 형식: type `PRODUCT`, targetService `product-service`, targetRef `SALE-STATE:{productId}`, 행 attributes `{saleState: "SALE_STOPPED"}`. 승인 수신은 `sale_state_grant`, 승인 하나는 한 번만 쓴다. 반영 시 SKU 마다 `ProductSaleStateChanged`(API `TEMPORARILY_SOLD_OUT`/`SALE_STOPPED` → 사건 `TEMP_OUT`/`DISCONTINUED`).
- 재적재 승인 초안에 `saleState` 가 없으면 현재 판매 상태를 유지한다(판매중지를 몰래 풀지 않음). 상품 현재 조회의 `saleState` 는 승인 스냅샷이 아니라 현재 상태다.
- `/sku-issuances` 는 새 번호를 만들지 않는다. SKU 는 승인 이벤트로 발급되고, 이 명령은 요청 판매 단위가 그 승인으로 발급·갱신된 SKU 와 같은지 대조한다(`APPROVAL_REQUIRED`·`BASE_PRODUCT_NOT_FOUND`·`APPROVAL_SCOPE_MISMATCH`).
- 구매 상한(`/internal/purchase-limit-checks`): 같은 기초상품 Σ 수량×구성 낱개 수 ≤ `pmart.product.purchase-limit-units`(기본 10, 비우면 CONFIG_REQUIRED). 승인 예외는 미구현.

## seed-v1 기준파일 (2026-11-02)
- P마트 카테고리 = B마트 트리(id `p09`, `p09-c01`, `p09-c01-l01`, …). 묶음 `product.json` 의 `primaryCategoryId`·`categoryIds` 가 있으면 그 id 로 연결하고, 없을 때만 이름 경로로 자동 매핑한다(`seedToInput` → `DraftRules`).
- `POST /category-tree-additions`: 묶음 `categories.json` 을 받아 **발효 트리 + 없는 노드만** 새 DRAFT 버전(기존 노드·필터 패널 유지, 이름·위치 충돌은 400 CATEGORY_CONFLICT, 더할 것 없으면 422 NOTHING_TO_ADD). 발효는 PRODUCT 승인. 승인 전 다른 버전이 발효되면 그 초안은 낡으므로 다시 만든다(`baseVersion`).
- 중량·보관 온도가 묶음에 없으면(대부분) 추정하지 않는다 → INVALID 초안, 운영자가 백오피스에서 채운다(사용자 결정 2026-11-02).
- `POST /internal/source-link-lookups`(2026-11-03, pricing 가격 일괄 등록용): (sourceSystem, seedProductId 목록) → `product_source_link` 의 발급된 상품·SKU, 발급 전·모르는 키는 `missing`. 서비스 주체만(internal-paths).
- `POST /category-tree-addition-uploads`(2026-11-03): 같은 일을 seed-v1 zip 으로 — 묶음의 `categories.json`(최상위·`seed-v1/` 아래)을 꺼내 위 경로와 같은 요청으로 처리(`SeedBundleReader.readCategories`). 없으면 422 BUNDLE_INVALID.
- 재업로드(BR-39, 2026-11-03): 이미 등록된 seed 상품 ID 를 다시 올리면 새 DRAFT 가 생기지만, 승인 반영은 `product_source_link` 로 기존 상품을 찾아 **새 버전**으로 갱신한다(SKU 유지, 판매 단위·구성 수가 다르면 SKU_IDENTITY_CHANGED). 심사 전 초안 응답의 `registration`(NEW/UPDATE)·`existingProductId`·`existingSkus` 와 승인 행 attributes 로 이를 드러낸다(`ProductDraftService.existingProduct`).
- `GET /product-import-batches/{id}/id-map` 은 묶음 `id-map.json` 모양(format·entries[seedProductId, identityKey, pmartProductId, sku]). `products` 는 호환용(같은 목록).

## 상태
상품 등록(초안·배치·카테고리 트리·승인 반영·SKU 발급), 조회, 판매 상태 변경, SKU 발급 확인, 구매 상한 검증 구현(2026-10-30).
