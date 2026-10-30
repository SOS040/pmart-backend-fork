# 상품 등록(임시 백오피스) 설계 `[초안]`

버전 0.1 · 2026-10-30 · 상태: 초안(결정 필요 항목 §8)

임시 백오피스에서 상품을 **개별 또는 일괄(배치)** 로 입력하면, 승인을 거쳐 상품·SKU·바코드·카테고리 정본이 만들어지고 CDC·MFC 에 그 SKU 가 적용되게 한다. 프론트엔드는 다른 팀(pmart-clients)이 만들고, 이 문서는 백엔드 API·테이블·흐름을 정한다.

근거: ADR-0001(상품 정본 product-service), ADR-0002(센터 유형별 재고 단일 기록자), ADR-0014(기준정보는 승인 경로로만), BackOffice BO-03(상품·SKU·바코드·환산 승인)·BO-05(전시·콘텐츠), Commerce 2.2·2.7(E/B SKU 확정 정책)·카테고리 확정 정책, WMS CDC-F-002(승인 상품·바코드 반영).

## 1. 지켜야 할 업무 규칙
| 규칙 | 근거 |
|---|---|
| SKU 는 `E-`(단품)·`B-`(밀봉 묶음) + **E/B 공유 전역 8자리 일련번호**. 발급은 본사 승인 후, 재사용 금지. `P-` 사용 금지 | Commerce 2.7 [확정], BO-03 |
| B 6입 1팩과 E 6개는 다른 SKU·다른 재고. E↔B 전환 없음. BOX/PLT 는 물류 단위이지 판매 SKU 가 아님 | Commerce 2.7, BO-03 |
| SKU 는 CDC·모든 MFC 공통. 센터가 자체 코드를 만들지 않고, 이동해도 SKU 가 바뀌지 않음. 수량·위치·취급 ON/OFF 는 센터별 | Commerce 2.2 [확정] |
| 제조사 바코드는 SKU 와 연결되는 스캔 표식(SKU 문자열과 달라도 됨). 선행 0 보존. **같은 조건에서 바코드 하나가 두 SKU 를 가리키면 자동 선택하지 않고 충돌** | Commerce 2.2, BO-03, CDC-F-002 |
| 단위·중량 미등록을 0 으로 채우지 않음(판매·운송 적격 보류) | BO-03 |
| 카테고리 트리는 **참고 자료 구조**: 상위(섹션 '맛있는것'·'편리한것') > 하위 > 칩1 > 칩2 의 4단계, 노드마다 필터 그룹. 상품은 다중 카테고리 + 대표 1개. 다중 노출이 SKU 를 복제하지 않음 | 사용자 결정 2026-10-30 (ADR-0015, Commerce 의 1·2단계 30/141 정책 대체) |
| 기준정보는 승인 결과로만 반영(승인 없는 쓰기 경로 없음), 자기승인 금지 | ADR-0014, approval-service |
| 고객 구매 가능 재고는 **담당 MFC 의 자기 재고만**(`A = E − R`). CDC·다른 MFC 수량을 합산하지 않음 | Commerce 2.2 [확정], ADR-0002 |

## 2. 서비스 책임
| 서비스 | 이번 기능에서 하는 일 |
|---|---|
| **product-service** | 상품 등록 초안(개별·배치) 접수·검증, 승인 요청 생성, 승인 후 SKU 발급·바코드·카테고리 매핑·버전 발행(`SkuIssued`, `ProductVersionPublished`, `CategoryChanged`) |
| **product-content-service** | 고객용 상품명·상세설명·이미지(대표·상세설명·상세정보) 초안과 게시(`ProductContentPublished`) |
| **approval-service** | 변경 요청(PRODUCT / DISPLAY)·승인 결정 → `ApprovalDecided` (이미 구현됨, 슬라이스 1) |
| **mfc-inventory-service** | `SkuIssued`/`ProductVersionPublished` 구독 → SKU 투영. MFC 별 취급은 **기본 OFF**(레코드 없음), MFC 운영자가 켠다. 고객 가용 조회(`/internal/availability`)의 정본 |
| **cdc-inventory-service** | 같은 이벤트 구독 → CDC SKU 취급(기본 OFF, CDC 운영자가 켬)·물류 규격 반영(CDC-F-002). 고객 가용에는 쓰이지 않음. 이번에 이 부분만 먼저 구현(사용자 결정 2026-10-30) |
| 이미지 저장소 | S3 호환 오브젝트 저장소. 로컬·LAN 은 **SeaweedFS `chrislusf/seaweedfs:4.48`**(수집 저장소 `code-and-pick/product-data` 와 같은 버전), 클라이언트 AWS SDK v2 `s3`(BOM 2.55.11). ADR-0016 |
| center-master-service | MFC·CDC 목록의 정본(`CenterMasterChanged`). 재고 서비스가 "어느 센터에 적용할지"를 여기서 안다 |

## 3. 흐름
```
[임시 백오피스]
  ① 개별: POST product-service /product-drafts              (상품 1건)
     배치: POST product-service /product-import-batches     (CSV/JSON N행) → 행별 검증 결과
  ② 콘텐츠: POST product-content /content-drafts (+ 이미지 업로드)   ← 같은 draftKey 로 연결
  ③ 제출: POST product-service /product-drafts/{id}/submissions  (배치는 /product-import-batches/{id}/submissions)
       → approval-service ChangeRequest(type=PRODUCT, lines=행별 lineKey) 생성·제출
  ④ 승인자(다른 운영자): approval-service 결정 APPROVE / REJECT
       → ApprovalDecided(platform.approval.v1)
  ⑤ product-service (InboxGuard): 승인 범위 검증 → SKU 발급(전역 일련번호) → 바코드·카테고리·물류 규격 저장
       → product_version 스냅샷 → Outbox: SkuIssued×N, ProductVersionPublished
  ⑥ mfc-inventory / cdc-inventory: 이벤트 구독 → 활성 센터마다 센터×SKU 취급 레코드를 handling=OFF 로 생성
       → 센터 운영자가 POST .../sku-handlings 로 ON (고객 판매는 MFC ON + 현물 있어야 가능)
  ⑦ product-content: ProductVersionPublished 수신 → 콘텐츠 초안을 productId 에 연결 → DISPLAY 승인 후 게시
```

### 3.1 배치 입력 형식 = 수집 저장소의 `seed-v1`
배치는 수집 저장소(`code-and-pick/product-data`)가 PMart 용으로 정한 묶음 형식 `seed-v1`(zip)을 받는다(형식 명세는 그 저장소 `docs/seed-format.md`).
```
seed-v1/
  manifest.json · categories.json(관찰 경로 → pmartCategoryId) · id-map.json · validation.json
  products/{seedProductId}/product.json, images/main.jpg, images/description_01..NN.jpg, images/info.jpg
```
- `product.json` 블록 → 서비스: `product`·`skus[0]` → product-service 초안, `content`·images → product-content 초안, `price` → pricing 가격 승인(별도).
- **멱등 키**: 각 초안·결과에 `source_system='bmart-capture'`, `source_key=seedProductId` 를 저장하고 그 키로 갱신(upsert). 두 번 올려도 상품 수 불변.
- `skus[0].sku` 는 항상 null(수집 쪽은 SKU 를 만들지 않음) → PMart 가 승인 후 발급하고 `id-map.json`(seedProductId → productId·sku)을 만들어 돌려준다.
- `skus[0]` 의 중량·보관 온도 등은 이름에서 읽은 **추정값**(`source.derived`)이라 초안으로만 받고, 승인 전에 운영자가 확인한다. 바코드는 항상 빈 값 → 배치 행에서 바코드는 선택(없으면 바코드 매핑 없이 SKU 발급).
- 이미지 role: `main.jpg`=MAIN, `description_NN.jpg`=DESCRIPTION(sortOrder 순), `info.jpg`=DETAIL.
- 개별 입력도 같은 `product.json` 모양을 쓴다(1건짜리 묶음).
- 배치는 **승인 요청 1건 + 행(line) N개**로 묶는다. 1차 approval 은 단일 승인(전체 승인/반려)이라, 일부 행만 고치려면 그 행을 빼고 다시 제출한다. 부분 승인(PARTIALLY_APPROVED)은 approval 확장 때.
- 검증 실패 행은 제출에서 빠지고 행별 오류(`BARCODE_CONFLICT`, `CATEGORY_NOT_FOUND`, `WEIGHT_REQUIRED` …)로 남는다. 성공 행만 제출할지 전체를 막을지는 요청 옵션(`submitMode=VALID_ONLY|ALL_OR_NOTHING`).
- 재전송: 모든 명령은 `Idempotency-Key`(RequestIdempotency). 같은 `ApprovalDecided` 재수신은 Inbox 로 1회만 적용, SKU 이중 발급 없음(승인 행 → 발급 SKU 매핑을 저장).
- 고객앱 구매 가능 판단: cart/order → mfc-inventory `/internal/availability`(쇼핑 주소의 담당 MFC 1곳). 반입·검수 전 SKU 는 가용 0 이 아니라 "취급 레코드 있음·현물 없음"으로 품절 표시.

## 4. API (계약 추가·변경 예정)
### product-service
| 메서드·경로 | 용도 |
|---|---|
| `POST /product-drafts` | 상품 1건 초안(기초상품, E/B, 구성 낱개 수, 중량, 보관 온도, 주류, 바코드 목록, 카테고리(대표 1 + 추가), 물류 규격) |
| `GET/PUT /product-drafts/{draftId}` | 조회·수정(제출 전) |
| `POST /product-drafts/{draftId}/submissions` | 승인 요청 생성(approval 호출) |
| `POST /product-import-batches` | 배치 업로드(`seed-v1` zip, multipart; 최대 N상품 `[결정 필요]`) → 상품별 검증. 이미지는 product-content 로 넘김(§3.1) |
| `GET /product-import-batches/{batchId}/id-map` | `id-map.json`(seedProductId → productId·sku) — 수집 저장소 회신용 |
| `GET /product-import-batches/{batchId}` · `/rows` | 진행 상태·행별 결과(발급 SKU 포함) |
| `POST /product-import-batches/{batchId}/submissions` | 유효 행 승인 요청 |
| `GET /categories/tree` (기존, 응답 변경) | 4단계 트리. `CategoryNode.depth`(1·2) → `level`(TOP/SUB/CHIP1/CHIP2) + `section` |
| `GET /categories/{categoryId}/filters` | 그 노드의 필터 패널(그룹·선택 방식·옵션, 화면 순서) |
| `POST /category-tree-versions` | 카테고리 트리·필터 묶음 등록·변경 초안(JSON, 참고 자료 형식) → 승인 대상 |
| 기존 `/sku-issuances` | 승인 결과로 발급하는 내부 명령. 위 흐름의 ⑤가 이것을 이벤트 구동으로 수행 |
| 기존 `/barcodes/{barcode}`, `/skus/{sku}`, `/internal/skus` | 조회 |

### product-content-service
| 메서드·경로 | 용도 |
|---|---|
| `POST /content-drafts` | 고객용 상품명·상세설명·보관/반품 안내 초안(`draftKey` 로 상품 초안과 연결) |
| `POST /content-drafts/{draftId}/images` | 이미지 업로드(role: `MAIN`=대표, `DESCRIPTION`=상세설명, `DETAIL`=상세정보 표) |
| `POST /content-import-batches` | 배치 콘텐츠·이미지 묶음 업로드(manifest + 이미지) |
| 기존 `/product-contents/{productId}/publications` | DISPLAY 승인 후 게시 |

## 5. 테이블
모든 서비스에 공통 `outbox_event`·`inbox_event`·`request_record` 는 이미 있다(common-messaging). 아래는 `V2__…` 이후 추가.

### 5.1 product-service
| 테이블 | 핵심 열 | 제약·메모 |
|---|---|---|
| `category_tree_version` | `version` PK, `state`(DRAFT/APPROVED/EFFECTIVE/SUPERSEDED), `approval_id`, `effective_from`, `source_note`, `created_at` | 트리·필터 전체를 버전으로 관리. 발효 시 `CategoryChanged` |
| `category_node` | (`version`, `category_id`) PK, `parent_id`, `level`(TOP/SUB/CHIP1/CHIP2), `section`(TOP 만: 맛있는것/편리한것), `name`, `sort_order`, `exposed` | 순수 트리(부모 1개). `category_id` 는 참고 자료의 구조 id(`p03`, `p03-c01`, `p08-c01-l01-s01`)를 그대로 쓴다 |
| `filter_group` | (`version`, `filter_group_id`) PK, `name`, `selection_mode`(MULTIPLE/TOGGLE/MIXED), `common` | 참고 자료 `filters.json` 1,409개. 이름이 같아도 옵션이 다르면 다른 그룹 |
| `filter_option` | (`version`, `filter_group_id`, `option_seq`) PK, `name` | 화면 순서 = `option_seq` |
| `category_filter` | (`version`, `category_id`, `filter_group_id`) PK, `sort_order` | 노드별 필터 패널(화면 순서) |
| `base_product` | `base_product_id` PK, `internal_name`, `created_at` | 구매 상한 합산 단위 |
| `product` | `product_id` PK, `base_product_id` FK, `internal_name`, `sale_state`(ON_SALE/TEMPORARILY_SOLD_OUT/SALE_STOPPED), `current_version`, `row_version` | 관리용 명칭만. 고객용 명칭은 content |
| `product_category` | (`product_id`, `category_id`) PK, `is_primary` | 어느 단계 노드에도 매핑 가능(참고 상품은 상위 `p03` 에만 매핑돼 있음). 대표 1개: `UNIQUE (product_id) WHERE is_primary` |
| `product_filter_option` | (`product_id`, `filter_group_id`, `option_seq`) PK | 상품이 어떤 필터 옵션에 걸리는지. 참고 자료에는 상품별 값이 없어 배치 입력의 선택 항목 |
| `sku` | `sku` PK(`E-00000001`), `serial_no` UNIQUE, `unit_type`(E/B), `product_id` FK, `base_product_id`, `units_per_sku`(≥1), `weight_g`(NOT NULL), `storage_type`(AMBIENT/CHILLED/FROZEN), `alcohol`, `state`(ACTIVE/INACTIVE), `issued_approval_id`, `issued_at` | `CHECK (sku = unit_type || '-' || lpad(serial_no, 8, '0'))`. 삭제 없음(비활성만) |
| `sku_serial_seq` (시퀀스) | E/B 공유 1개 | 재사용 금지: 시퀀스만 증가, 반환 없음 |
| `sku_barcode` | `barcode_id` PK, `barcode`(text, 선행 0 보존), `sku` FK, `barcode_type`(GTIN_8/GTIN_13/GTIN_14/UPC_A/OTHER), `valid_from`, `valid_to`, `state`(ACTIVE/RETIRED), `approval_id` | **활성 바코드 1개 = SKU 1개**: `UNIQUE (barcode) WHERE state='ACTIVE'`. 충돌은 `BARCODE_CONFLICT` 로 거절(자동 선택 없음) |
| `sku_logistics_spec` | (`sku`, `spec_version`) PK, `units_per_box`, `boxes_per_pallet`, `ti`, `hi`, `approval_id`, `effective_from` | BOX/PLT 환산(ADR-0001 결정). 과거 버전 보존 |
| `product_version` | (`product_id`, `version`) PK, `approval_id`, `snapshot` jsonb, `published_at` | 주문·검색이 특정 버전을 조회(`/internal/products/{id}/versions/{v}`). 불변 |
| `product_source_link` | (`source_system`, `source_key`) PK, `product_id`, `skus` jsonb, `last_batch_id`, `updated_at` | seed-v1 upsert 기준. `id-map.json` 생성 원천 |
| `product_import_batch` | `batch_id` PK, `source_name`, `format`(SEED_V1/JSON), `source_system`, `row_count`, `valid_count`, `invalid_count`, `state`(UPLOADED/VALIDATED/SUBMITTED/APPROVED/APPLIED/PARTIALLY_APPLIED/REJECTED), `change_request_id`, `created_by`, `created_at` | |
| `product_draft` | `draft_id` PK, `batch_id` FK NULL, `row_no`, `draft_key`(클라이언트 행 키), `source_system`, `source_key`, `derived_fields` jsonb(추정값 표시), `payload` jsonb, `validation_state`(VALID/INVALID), `errors` jsonb, `state`(DRAFT/SUBMITTED/APPROVED/APPLIED/REJECTED), `change_request_id`, `approval_line_key`, `result_product_id`, `result_skus` jsonb | 개별 입력은 `batch_id` 없음. `UNIQUE (batch_id, draft_key)` |
| `approval_application` | `approval_id` PK, `change_request_id`, `applied_at`, `result` jsonb | 같은 승인 재적용 방지(승인 → 발급 결과) |

### 5.2 product-content-service
| 테이블 | 핵심 열 | 제약·메모 |
|---|---|---|
| `content_draft` | `draft_id` PK, `draft_key`, `product_id` NULL(상품 승인 후 연결), `name`, `description`, `storage_guide`, `return_guide`, `notices` jsonb, `state`, `change_request_id` | |
| `content_image` | `image_id` PK, `draft_id` FK, `role`(MAIN/DESCRIPTION/DETAIL), `sort_order`, `bucket`, `object_key`, `content_type`, `width`, `height`, `bytes`, `sha256`, `ocr_text` NULL | 파일 본체는 S3(SeaweedFS), DB 에는 키·메타만. 같은 `sha256` 이면 다시 올리지 않음 |
| `product_content` | (`product_id`, `content_version`) PK, `product_version`, `name`, `description`, …, `published_at`, `approval_id` | 게시본. 과거 주문 스냅샷은 order 가 보관 |
| `product_projection` | `product_id` PK, `latest_product_version`, `sale_state`, `primary_path_id` | `ProductVersionPublished` 투영(조회용 사본) |

### 5.3 mfc-inventory-service / cdc-inventory-service (이번에는 취급 레코드만)
| 테이블 | 핵심 열 | 메모 |
|---|---|---|
| ~~`center_projection`~~ | `center_id` PK, `center_type`, `state` | **보류(2026-10-30)**: center-master 미구현이라 만들지 않았다. 취급은 레코드 없음 = OFF 라서 센터 목록 없이 같은 동작이 된다 |
| `sku_projection` | `sku` PK, `product_id`, `base_product_id`, `unit_type`, `units_per_sku`, `weight_g`, `volume_ml`, `storage_type`, `alcohol`, `product_version`, `state`, `updated_at` | `SkuIssued`/`ProductVersionPublished` 투영. 오래된 `product_version` 이벤트는 무시. CDC 는 물류 규격(`units_per_box`·`boxes_per_pallet`·`ti`·`hi`)도 |
| `center_sku_handling` | (`center_id`, `sku`) PK, `handling_enabled`(기본 **false**), `changed_by`, `reason`, `updated_at`, `row_version` | 센터 취급 ON/OFF(센터별 정본). 기본 OFF, 센터 운영자가 켬(사용자 결정 2026-10-30). **구현: 미리 OFF 행을 만들지 않고 레코드 없음 = OFF**, 운영자가 바꿀 때 행이 생긴다(이벤트로는 만들지 않음) |
| `center_sku_handling_history` | `history_id` PK, `center_id`, `sku`, `from_enabled`, `to_enabled`, `changed_by`, `reason`, `changed_at` | 취급 변경 이력 |

센터 취급 변경 API(두 서비스 같은 모양, 경로는 `/mfcs/{mfcId}/…`·`/cdcs/{cdcId}/…`): `POST …/sku-handlings`(본문 `sku`, `enabled`, `reason`; `Idempotency-Key`·`X-Expected-Version`(레코드 없음 = 0)) · `GET …/sku-handlings?enabled=` · 일괄 `POST …/sku-handling-batches`(500행, 전부 또는 전무). 권한은 그 센터 운영자(토큰 `centers` 클레임). 켜기는 ACTIVE SKU 만(`SKU_INACTIVE`), 투영 전이면 `SKU_NOT_FOUND`. 서비스 간 `GET /internal/skus/{sku}`(투영 조회).
- 재고 수량 테이블(Balance·Ledger·Reservation)은 슬라이스 4(mfc)에서 만든다. 상품 등록만으로 수량은 생기지 않는다(초기 재고는 mfc-inbound 반입 → 검수, ADR-0014).

## 6. 참고 자료와의 대응 (local-reference, Git 제외)
| 참고 자료 | 들어갈 곳 |
|---|---|
| `products/manifest.json` 의 name / priceText / unitText / categoryPaths | content.name · pricing(가격 승인, 별도) · product 단위·중량 · product_category(경로 이름 → `category_id`) |
| `{productId}/thumbnail.jpg` / `detail.jpg` / `info.jpg` | content_image role MAIN / DESCRIPTION / DETAIL |
| manifest `info`(상세정보 행) · `description`(OCR) | content.notices·description 초안(검수 필요) |
| `categories/categories.json` | category_node(상위 32·하위 164·칩1 293·칩2 40, 상위의 `section`) + category_filter |
| `categories/filters.json` | filter_group(1,409) + filter_option |
| `categories.json` 의 `recommendationShortcuts`(기획전·잘팔려요 …) | 넣지 않음 — 프로모션·추천 전시는 1차 제외 |
- 참고 자료에는 **SKU·바코드·중량·보관 온도가 없다**. 배치 입력 파일에 운영자가 채워야 하며, 비면 행 검증에서 `*_REQUIRED` 로 막는다(0 으로 채우지 않음).
- 가격(`price_value`)은 pricing-service 의 가격 승인(PRICE)으로 따로 넣는다.

## 7. 구현 순서(제안)
1. 계약: product-service·product-content 경로 추가, `ProductVersionPublished` 에 `barcodes`·`storageType` 필드 추가(필드 추가만 → v1 유지)
2. product-service V2: 카테고리·상품·SKU·바코드·버전·초안·배치 + ApprovalDecided(PRODUCT) 반영
3. product-content V2: 초안·이미지·게시 + 이미지 저장소 어댑터
4. mfc-inventory / cdc-inventory: 센터·SKU 투영과 취급 레코드
5. 적재 스크립트(`deploy/lan/seed/`)로 참고 자료 → 배치 업로드 → 승인 → 반영 회귀 시험(참고 자료는 저장소 밖에서 읽음)

## 8. 결정 필요
| # | 질문 | 영향 |
|---|---|---|
| ~~P1~~ | **결정(2026-10-30)**: 카테고리는 참고 자료대로 4단계 + 필터. ADR-0015 | category·filter 테이블 |
| ~~P2~~ | **결정**: S3 호환 저장소, 수집 저장소와 같은 SeaweedFS 4.48 + AWS SDK v2 2.55.11. ADR-0016 | product-content 어댑터, deploy 구성 |
| ~~P3~~ | **결정**: 센터 취급 기본 OFF, 각 센터(MFC) 운영자가 켬 | center_sku_handling |
| ~~P4~~ | **결정**: cdc-inventory 의 SKU 투영·취급·물류 규격 반영을 이번에 구현 | 구현 범위 |
| P6 | 수집 묶음의 `categories.json` 은 `pmartCategoryId` 가 모두 null. 우리 트리 id 가 참고 자료 구조 id(`p03` …)라 경로 이름으로 자동 매핑해도 되는지(매핑 실패 상품은 보류) | 적재기 |
| P7 | 가격: seed `price.basePrice` 는 화면 표시가(할인가일 수 있음). 기본 판매가 초안으로 그대로 쓸지 | pricing 승인 초안 |
| P5 | 배치 최대 행 수, 승인자 계정(자기승인 금지라 입력자와 다른 운영자 필요) | 운영 |
