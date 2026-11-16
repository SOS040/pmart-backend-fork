보낸 영역: platform(PC 1)

# [pmart-backend → pmart-clients] 요청: 백오피스 "상품 정보 수정" 화면 (2026-11-17)

- 받는 앱: backoffice
- 결정: 사용자 2026-11-17 "상품 정보 수정 + 승인 기능 구현" — 기존 상품을 고쳐 PRODUCT 승인으로 새 버전을 만든다(바코드 추가·교체 포함).
- 계약: `product-service.yaml` **0.3.0** `POST /api/v1/products/{productId}/edit-drafts`. 그 뒤는 기존 초안 경로 그대로(`PUT /product-drafts/{draftId}`, `POST /product-drafts/{draftId}/submissions`, approval-service `POST /change-requests` → `/submissions`). 백엔드 커밋은 PC 1 브랜치, **LAN 배포 전** — 배포되면 알린다.

## 왜 필요한가
지금까지는 수정하려면 `GET /products/{id}` 의 값을 손으로 옮겨 `targetProductId` 초안을 만들어야 했다. **재승인은 초안 값으로 바꾸므로** 빠뜨린 바코드·치수·소비기한 보장·용량은 승인 때 지워진다. 새 경로는 서버가 현재 승인 값을 전부 옮겨 담은 초안을 만들어 주므로, 화면은 그 초안의 `input` 을 **통째로** 폼에 채우고, 고친 뒤 **통째로** PUT 하면 된다(필드를 골라 보내지 말 것 — 빠진 필드는 지워진다).

## 1. 상품 상세 → "수정 시작" 버튼
- 상품 상세(`GET /products/{productId}`) 화면에 **수정 시작** 버튼.
- 누르면 `POST /api/v1/products/{productId}/edit-drafts` (본문 없음, `Idempotency-Key`·`X-Source-System` 헤더 필수 — 다른 POST 와 같음). 운영자(OPERATOR) 누구나(역할 제한 없음 — 상품 초안 만들기와 같은 규칙).
- 응답 **201** = 보통 초안 응답(`ProductDraft`): `draftId`, `version`, `validationState`, `problems`, `registration: "UPDATE"`, `existingProductId`, `existingSkus`, `input`.
- **409 `EDIT_DRAFT_EXISTS`**: 이 상품에 이미 열린(DRAFT·SUBMITTED) 수정 초안이 있다. `details[0] = {field: "existingDraftId", reason: "<draftId>"}` — "이미 수정 중인 초안이 있습니다" 안내와 함께 **그 초안으로 이동**하게 한다(SUBMITTED 면 승인 대기 화면으로).
- **404 `PRODUCT_NOT_FOUND`**: 없는 상품.

## 2. 수정 초안 폼 (현재 값으로 채움)
`input` 에 오는 것(모두 현재 승인 상태 그대로):
- `targetProductId`(숨김, 바꾸지 말 것)
- `product`: `internalName`, `baseProductId`(표시만 — 바꿔도 반영 안 됨), `primaryCategoryId`, `categoryIds`
- `skus[0]`: `unitType`·`unitsPerSku`(표시만 — 바꾸면 승인 반영 때 `SKU_IDENTITY_CHANGED` 로 거절), `weightG`, `volumeMl`, `storageType`, `alcohol`, `shelfLifeGuarantee`{minRemainingDays, maxDaysSinceManufacture}, `dimensions`{widthMm, depthMm, heightMm} + `dimensionsSource`(MEASURED/ESTIMATED), `logistics`{unitsPerBox, boxesPerPallet, ti, hi}, `barcodes`(활성 바코드 전부)
- `filterOptions`
- 판매 상태(`product.saleState`)는 **넣지 않는다** — 반영 때 현재 상태 유지. 판매 상태는 기존 판매 상태 변경 화면으로.
- 저장: `PUT /api/v1/product-drafts/{draftId}` 본문 = 고친 `input` 전체, `X-Expected-Version: <version>` 권장(409 `VERSION_CONFLICT` 면 다시 불러오기). 응답의 `validationState`·`problems` 를 필드 옆에 보여 준다(`field` 가 `skus[0].barcodes` 등 경로).
- 처음 받은 초안이 이미 INVALID 일 수 있다(현재 값이 새 규칙에 안 맞는 경우 — 예: LAN 초기 시험 바코드 `2990000000011` 등 검사 숫자 틀린 것). 그때는 문제를 보여 주고 고치게 한다.

## 3. 바코드 추가·교체
- 바코드 목록 편집: 추가(입력·스캔), 삭제. **목록에서 뺀 바코드는 승인 때 퇴역**(그 SKU 에서 해제)되고, 새로 넣은 것은 연결된다. 교체 = 옛것 삭제 + 새것 추가.
- 선행 0 을 지우지 말 것(문자열 그대로).
- 검증 코드(저장 응답 `problems[].code`):
  - `BARCODE_CHECK_DIGIT_INVALID` — 숫자만 8·12·13·14자리인데 GS1 검사 숫자가 틀림. `detail` 에 맞는 끝자리가 온다. 그 밖의 형식(문자 포함·다른 길이)은 검사하지 않는다.
  - `BARCODE_CONFLICT` — 다른 상품 SKU 에 활성으로 연결된 바코드(`detail` 에 그 SKU). 자동으로 옮기지 않는다.
  - `BARCODE_DUPLICATED_IN_DRAFT`, `BARCODE_EMPTY`
- 가능하면 입력할 때 검사 숫자를 화면에서도 미리 확인해 주면 좋다(서버가 정본 — 화면 검사는 편의).

## 4. 제출 → 승인 상태
- VALID 일 때만 **제출** 버튼 활성: `POST /api/v1/product-drafts/{draftId}/submissions` → `approvalRequest` 를 approval-service 에 그대로(기존 상품 등록 화면과 같은 흐름). 승인 행 attributes 에 `registration: "UPDATE"`·`existingProductId`·`existingSkus` 가 있어 승인자가 "기존 상품 수정"임을 본다.
- 승인 상태: 초안 `GET /product-drafts/{draftId}` 의 `state` — `SUBMITTED`(승인 대기) → `APPLIED`(반영, `resultProductId`·`resultSkus` 는 같은 상품·같은 SKU) / `REJECTED`(`problems` 끝에 사유: `APPROVAL_REJECTED`, `BARCODE_CONFLICT`(승인 사이에 다른 상품이 가져감), `SKU_IDENTITY_CHANGED` 등). 승인 ID 는 approval-service `GET /approvals?targetRef=productDraft:{draftId}`.
- 반영되면 상품 상세의 `version` 이 1 오르고 새 값이 보인다. 그 뒤 다시 "수정 시작" 가능(열린 초안이 없어지므로).
- 화면 권장: 상품 상세에 "수정 중(초안 …, 승인 대기)" 배지 — 수정 시작을 눌러 409 가 오면 그 초안 id 로 표시할 수 있다.

## 회신
`docs/handoff/clients/` 규칙대로 pmart-clients 쪽 회신 파일로.
