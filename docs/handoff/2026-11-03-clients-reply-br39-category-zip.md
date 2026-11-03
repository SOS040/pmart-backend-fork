# [pmart-backend → pmart-clients] 회신: BR-39(재업로드 = 기존 상품 갱신 표시) · 카테고리 추가 zip 업로드 (2026-11-03)

- 요청: `pmart-clients/docs/handoff/2026-11-03-backend-product-import-batch.md` §2(BR-39). BR-38(배치 수 갱신)은 이번 회신에 없다 — 백오피스의 행 기준 집계를 유지해 달라(따로 회신).
- 계약: `contracts/openapi/product-service.yaml` — `ProductDraft` 필드 추가(`registration`·`existingProductId`·`existingSkus`), 새 경로 `POST /category-tree-addition-uploads`. 기존 필드·경로는 바뀌지 않는다.
- 게이트웨이: pmart-config `api-gateway.yml` 에 `/api/v1/category-tree-addition-uploads` 추가.

## 1. BR-39 — 요청서의 영향 설명 정정
- 현상(새 초안이 생김)은 맞다. 그러나 **승인해도 상품·SKU 가 하나 더 발급되지 않는다.**
  - `product_source_link` 의 기본키가 (sourceSystem, seedProductId) 라 seed 하나에 상품 하나다.
  - 승인 반영(`ApprovalApplier`)이 이 연결을 먼저 찾아, 있으면 **그 상품의 새 버전**으로 갱신한다(이름·카테고리·중량·보관 온도·바코드, SKU 유지). 판매 단위(E/B)·구성 낱개 수가 다르면 `SKU_IDENTITY_CHANGED` 로 반영을 거절한다. 초안에 판매 상태가 없으면 현재 상태를 유지한다.
  - 바코드 검사는 그 상품 자신의 SKU 를 충돌로 보지 않는다.
  - 이 동작은 이미 통합 시험(`RegistrationFlowIT` "같은 묶음을 다시 올려도 같은 상품 — 승인하면 새 버전, SKU 재발급 없음")으로 지키고 있다.
- 진짜 문제는 **운영자·승인자가 그 행이 새 등록인지 기존 상품 갱신인지 모른다**는 것이었다. 그래서 표시를 더했다(요청서의 (b)에 가깝지만 갱신을 막지 않는다).

## 2. 새 필드 — 초안(`ProductDraft`)
심사 전 초안(`DRAFT`·`SUBMITTED`)에만 채운다. 반영·반려된 초안은 `registration: null`(결과는 `resultProductId`·`resultSkus`).

| 필드 | 값 |
|---|---|
| `registration` | `NEW`(처음 등록) / `UPDATE`(이미 등록된 seed — 승인하면 기존 상품의 새 버전) |
| `existingProductId` | `UPDATE` 일 때 갱신될 상품 ID |
| `existingSkus` | `UPDATE` 일 때 그 상품의 SKU(유지된다) |

같은 표시가 `POST /product-import-batches`·`GET …/{batchId}` 의 `drafts[]`, `GET /product-drafts/{id}`, `PUT /product-drafts/{id}` 응답에 나온다.

**승인 요청 행**(`POST …/submissions` 의 `approvalRequest.lines[].attributes`)에도 `registration`, `UPDATE` 면 `existingProductId`·`existingSkus` 가 들어간다 — 승인 화면에서 "기존 상품 갱신"을 보여 주면 된다.

### 제안 (백오피스)
- 일괄 등록 표에 "구분" 열: `NEW` 새 상품 / `UPDATE` 기존 상품 갱신(상품 ID 링크).
- `UPDATE` 행이 있으면 제출 전에 "기존 상품 N개가 이 묶음 값으로 바뀝니다" 안내.
- 승인 상세: 행 `attributes.registration` 이 `UPDATE` 면 표시.
- 콘텐츠 일괄 등록은 상품 하나에 콘텐츠 한 벌이라 재업로드가 원래 "그 상품의 새 콘텐츠 버전"이다(이번 변경 없음).

## 3. 카테고리 추가 — zip 업로드 (새 경로)
`POST /api/v1/category-tree-addition-uploads` (multipart `file` = seed-v1 zip, 헤더 `Idempotency-Key`·`X-Source-System`)
- 상품·콘텐츠·가격 일괄 등록과 **같은 zip** 을 그대로 올리면, 서버가 묶음의 `categories.json`(최상위 또는 `seed-v1/` 아래)을 꺼내 **`POST /category-tree-additions` 와 같은 규칙**으로 처리한다.
- 응답·오류·승인 흐름이 같다: `201 CategoryTreeVersion`(`addedCategoryIds`·`skippedPaths`·`approvalRequest`), `400 CATEGORY_CONFLICT`, `422 NOTHING_TO_ADD`. 추가로 `categories.json` 이 없거나 형식이 다르면 `422 BUNDLE_INVALID`.
- 기존 JSON 경로는 그대로다. 화면은 파일 선택에서 `.zip`·`.json` 을 둘 다 받고, 확장자로 경로를 고르면 된다.

## 4. LAN
- 백엔드 배포 후 쓸 수 있다(배포 시점은 별도 안내).
