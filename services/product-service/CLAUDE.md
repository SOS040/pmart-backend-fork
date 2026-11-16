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
- SKU 소비기한 보장 `shelfLifeGuarantee`(ADR-0041, 2026-11-07, V4): `minRemainingDays`·`maxDaysSinceManufacture`(선택, 0~3650, 벗어나면 `SHELF_LIFE_INVALID`). 초안 → PRODUCT 승인으로 반영, 재적재 승인은 초안 값으로 바꾼다(없으면 보장 없음). `SkuDetail`·`ProductVersionPublished.skus[]` 에 나간다. 고시 문구 해석은 하지 않는다(등록 쪽 몫).
- 원천 키 없는 상품의 속성 변경(2026-11-07): 초안 `targetProductId` → registration=UPDATE, 승인 반영은 그 상품의 새 버전(SKU 유지, `ProductDraftService.updateTarget` 을 초안 검증·제출 표시·`ApprovalApplier` 가 같이 쓴다). 없는 상품 `TARGET_PRODUCT_NOT_FOUND`, 원천 키가 다른 상품에 연결 `TARGET_CONFLICT`. `SkuDetail.volumeMl` 추가(조회로 만든 수정 초안이 용량을 잃지 않게).
- **SKU 실물 치수(2026-11-16, 계약 0.2.0, V5, 사용자 결정 A안)**: SKU `dimensions`{widthMm·depthMm·heightMm}(선택, 셋 다 또는 전무, 1~5000 mm) + `dimensionsSource`(MEASURED·ESTIMATED, 선택 — 치수와 함께만). 벗어나면 `DIMENSIONS_INVALID`(`DraftRules`), 0·기본값으로 채우지 않는다. 판매 단위(E 낱개 / B 묶음 전체) 외곽 세 변. 초안(개별·seed `product.json` 의 skus[0].dimensions·dimensionsSource 그대로 — 추정값 일괄 적재) → PRODUCT 승인 → 새 버전 → `SkuDetail`·상품 버전 조회·`ProductVersionPublished.skus[]`. **재승인(재적재·targetProductId)은 소비기한 보장과 같이 초안 값으로 바꾼다 — 초안에 치수가 없으면 치수 미등록으로 지워진다**(승인 버전이 정본, 수정 초안은 조회의 dimensions 를 옮겨 담아야 유지). 구독 mfc-inventory 가 칸 용량 계산에 쓴다.
- **상품 정보 수정(2026-11-17, 계약 0.3.0, 마이그레이션 없음, 사용자 결정 "상품 정보 수정 + 승인 기능 구현")**: `POST /products/{productId}/edit-drafts`(운영자 누구나 — 초안 만들기와 같은 `Operator.current`, 멱등키) → 현재 상태(`ProductSnapshots.build` — 상품·첫 SKU·활성 바코드·치수+출처·소비기한 보장·용량·물류 규격·카테고리·필터 옵션)를 옮긴 `targetProductId` 초안(`ProductDraftService.createEdit`/`editInput`). 판매 상태·원천 키는 넣지 않는다(반영 때 현재 판매 상태 유지, 재적재 초안과 섞이지 않게). 상품 행 `FOR UPDATE` 로 줄 세우고, 그 상품을 가리키는 열린(DRAFT·SUBMITTED) 초안(`input->>'targetProductId'`, `DraftStore.findOpenForTarget`)이 있으면 409 `EDIT_DRAFT_EXISTS`(details[0] = {field: existingDraftId, reason: draftId}). 없는 상품 404 `PRODUCT_NOT_FOUND`. 이후는 PUT·제출·승인 그대로 — **수정은 이 초안의 input 을 통째로 고쳐 PUT**(뺀 바코드는 승인 때 퇴역, 빠진 치수·보장은 지워짐). 주의: `POST /product-drafts` 에 targetProductId 를 직접 넣는 옛 경로에는 이 중복 막기가 없다.
- **바코드 GS1 검사 숫자(2026-11-17)**: 숫자(ASCII)만 8·12·13·14자리면 검사 숫자 확인 → 틀리면 `BARCODE_CHECK_DIGIT_INVALID`(`BarcodeType.checkDigitValid`, `DraftRules`), 그 밖 형식은 검사 안 함. 다른 상품 활성 바코드는 기존 `BARCODE_CONFLICT`(초안 검증 + 반영 때 다시). 시험은 `RegistrationFlowIT.gtin()/randomGtin13()` 으로 검사 숫자가 맞는 값을 쓴다. LAN 초기 시험 바코드 17개(`2990000000011` 등 — `deploy/lan/seed/seed-data.json` SEED-001~007 포함)는 검사 숫자가 틀려 그 상품의 수정 초안은 처음부터 INVALID — 수정 흐름으로 바코드를 바꿔야 한다(새 환경에서 seed 를 돌리면 그 7개 초안이 INVALID).
