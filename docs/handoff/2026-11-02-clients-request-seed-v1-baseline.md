# [pmart-backend → pmart-clients] 요청: seed-v1 기준파일로 상품·콘텐츠 등록 (백오피스) (2026-11-02)

- 성격: **백오피스 보완 요청 + 백엔드 변경 안내.**
- 기준 자료: 사용자가 정한 새 묶음 형식 `seed-v1`(기준파일.zip). 내부 개발용 B마트 화면 캡처, 외부 공개·재배포 금지. 묶음 안 `FORMAT.md` 가 설명서다.
- 계약: `contracts/openapi/product-service.yaml` — `POST /category-tree-additions`(신규), `CategoryTreeVersion.baseVersion·addedCategoryIds·skippedPaths`(신규), `IdMap.format·entries·note`·`IdMapEntry.identityKey`(신규, `products` 는 deprecated)
- 백엔드 커밋: 이 문서와 같은 커밋.

## 1. 묶음 모양 (화면에 쓰는 부분만)
```
seed-v1/
  categories.json                 이 묶음 상품이 속한 경로 → P마트 카테고리 id (P마트 트리 = B마트 트리)
  id-map.json                     PMart 가 채워 돌려줄 파일
  products/{seedProductId}/
    product.json                  상품·SKU·콘텐츠(이름·옵션·설명·고시)·가격·출처
    images/main.jpg               MAIN  — 카드·상세 대표
    images/description_NN.jpg     DESCRIPTION — 상품설명(sortOrder 순으로 세로로 이어 붙임)
    images/info.jpg               DETAIL — 상세정보(고시) 표
    captures/…                    수집 원본. 백엔드는 읽지 않는다(화면에 쓰지 않음)
```

## 2. 백엔드 변경
1. **상품 초안이 묶음의 카테고리 id 로 연결된다.** `product.primaryCategoryId`·`categoryIds`(예 `p09-c01-l01`)가 있으면 그 id 로, 없을 때만 이름 경로로 찾는다. 트리에 그 id 가 없으면 `CATEGORY_NOT_FOUND`.
2. **`POST /category-tree-additions` (신규)** — 묶음의 `categories.json` 본문을 **그대로** 보내면, 현재 발효 트리를 복사하고 **없는 노드만** 더한 트리 초안(DRAFT)을 만든다.
   - 기존 노드·필터 패널은 바꾸지 않는다. `pmartCategoryIdLevels` 가 `null` 인 경로는 건너뛰고 `skippedPaths` 로 돌려준다.
   - 응답은 `CategoryTreeVersion`(기존 트리 초안과 같음) + `baseVersion`(복사한 발효 버전) + `addedCategoryIds`(새로 더한 노드, 상위부터).
   - 발효는 기존과 같다: 응답의 `approvalRequest` → approval-service `POST /change-requests` → PRODUCT 승인.
   - 오류: 같은 id 가 다른 이름·위치로 있거나 같은 이름 경로가 다른 id 로 있으면 **400 `CATEGORY_CONFLICT`**(메시지에 건별 사유). 모두 이미 있으면 **422 `NOTHING_TO_ADD`** — "추가할 카테고리 없음"으로 보이고 다음 단계로 넘어가면 된다.
   - 승인 전에 다른 트리 버전이 발효되면 이 초안은 그 변경을 담지 않는다. 승인 화면에서 `baseVersion` 이 현재 발효 버전과 다르면 "다시 만들기"를 권한다.
3. **id-map 응답이 묶음 `id-map.json` 모양**이 됐다: `{format: "seed-v1", batchId, sourceSystem, entries: [{seedProductId, identityKey, pmartProductId, sku, state}], note}`. 지금 화면의 "id-map.json 내려받기"는 응답을 그대로 저장하므로 바꿀 것 없다. `products` 는 같은 목록으로 남겨 두었고(호환) 나중에 뺀다 — `entries` 를 쓴다.
4. 정책(사용자 결정 2026-11-02): 묶음의 `images/` 를 LAN·로컬 저장소에 내부 확인용으로 올려도 된다. 운영에는 올리지 않는다.

## 3. 백오피스 요청 — 기준파일 한 번에 등록하는 순서
같은 zip 하나로 진행한다. 화면을 새로 만들지 않고 지금 화면(카테고리 트리·상품 일괄 등록·콘텐츠 일괄 등록)을 순서대로 안내해도 된다.

1. **카테고리 추가** (카테고리 트리 화면)
   - zip 을 고르면 브라우저에서 `seed-v1/categories.json` 만 꺼내 `POST /category-tree-additions` 로 보낸다(또는 운영자가 categories.json 파일을 따로 고르게 해도 된다).
   - 결과에 `addedCategoryIds`·`skippedPaths` 를 보여 주고 승인 요청 → 승인 → 발효 확인(`GET /category-tree-versions/{v}` 가 EFFECTIVE).
   - 422 `NOTHING_TO_ADD` 면 바로 2단계.
2. **상품 일괄 등록** (지금 화면 그대로: `POST /product-import-batches`)
   - **중량(g)·보관 온도는 묶음에 대부분 없다.** 백엔드는 추정하지 않으므로 그 초안은 `INVALID`(`WEIGHT_REQUIRED`·`STORAGE_TYPE_REQUIRED`)로 들어온다. **운영자가 채울 수 있게** 해 달라:
     - 배치 결과 목록에서 INVALID 행을 열어 `PUT /product-drafts/{draftId}`(지금 개별 초안 수정, 입력 전체를 보냄)로 `skus[0].weightG`·`storageType` 을 넣고 다시 검증.
     - 여러 건을 한 번에 채우는 표 입력이 있으면 좋다(상품명·용량 `volumeMl` 을 옆에 보여 주면 판단이 쉽다). 값을 미리 채워 넣지는 말 것.
   - 모두 VALID 가 되면 배치 제출 → PRODUCT 승인 → SKU 발급.
3. **콘텐츠 일괄 등록** (지금 화면 그대로: `POST /content-import-batches`, 같은 zip)
   - 상품 승인 뒤 올리면 원천 키(seedProductId)로 상품에 바로 연결된다. 먼저 올렸으면 상품 승인 후 연결된다.
   - 제출 → DISPLAY 승인 → 게시. 이미지 role 은 묶음 `content.images[]` 를 따른다.
4. **가격**: 이번 변경에 없음. 지금처럼 가격 화면에서 등록·PRICE 승인. 묶음 `price.basePrice` 를 가격 초안으로 자동으로 넣는 것은 따로 안내한다(백엔드 검토 중).
5. **id-map.json 내려받기**: 지금 버튼 그대로.

## 4. 주의
- 업로드 상한은 300MB(product·product-content 둘 다). 기준파일(166MB)은 들어간다. 더 큰 묶음(예: 2GB 대 묶음)은 나눠서 올려야 한다.
- 묶음 이미지는 화면 캡처라 `main.jpg` 에 B마트 담기 버튼(＋)이, `info.jpg`·고시 글자에 B마트 판매자·고객센터 정보가 들어 있다. 내부 확인용으로만 보이고 외부 공개 화면에 쓰지 않는다.

## 5. LAN 반영
- 아직 LAN 에 배포하지 않았다(product-service·api-gateway 이미지 필요). 배포하면 이 절에 기록한다.
