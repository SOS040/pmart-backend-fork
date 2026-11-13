# [pmart-backend → pmart-clients] 안내: 가격 일괄 등록 (백오피스) (2026-11-03)

- 성격: **백오피스 요청 + 새 API 안내.** `2026-11-02-clients-request-seed-v1-baseline.md` §3-4("가격은 따로 안내한다")의 회신이다.
- 계약: `contracts/openapi/pricing-service.yaml` — tag `registration`, `/price-import-batches/**`(신규). 게이트웨이 경로 추가(pmart-config `19f21bc`, `7bcceb4`).
- 백엔드 커밋: `05a3b76`, `b7740a6`(LAN 확인 후 수정).
- 사용자 결정(2026-11-03): 가격은 **백엔드가 묶음 `price.basePrice` 로 미리 채우고**, 운영자가 행마다 고치거나 빼고 공통 효력 시각을 정해 **PRICE 승인 요청 한 건**으로 올린다. 대상은 가격이 없던 SKU(새 가격)와 현재 가격과 다른 SKU(가격 변경). 현재 가격과 같은 SKU 는 뺀다.
- **백오피스에서 zip 을 풀거나 가격 칸을 미리 채우는 기능은 만들지 않는다** — 서버가 한다.

## 1. 순서 (기준파일 등록 흐름의 4단계)
상품 일괄 등록이 승인되어 SKU 가 발급된 뒤에 한다(그 전이면 행이 `SKU_NOT_FOUND`).

1. **올리기** `POST /price-import-batches` (multipart `file` = 상품·콘텐츠 일괄 등록과 **같은 seed-v1 zip**, 헤더 `Idempotency-Key`·`X-Source-System`) → `201 PriceImportBatch`.
   - 상한 300MB. 서버는 `product.json` 만 읽는다.
   - 오류: 422 `BUNDLE_INVALID`, 503 `DEPENDENCY_UNAVAILABLE`(product-service 조회 실패 — 다시 올리면 된다).
2. **표 보여 주기** — `lines[]` 한 줄 = 묶음 상품 하나. 열 제안: 상품명(`productName`) · SKU · 현재 가격(`currentPrice`, 없으면 "없음") · 묶음 가격(`bundlePrice`) · **등록 가격(`basePrice`, 입력 칸)** · 구분(`changeType`: NEW 새 가격 / CHANGE 가격 변경 / UNCHANGED 변경 없음) · 상태(`status`) · 빼기(`excluded` 체크).
   - `status`: `READY` 제출 대상 · `EXCLUDED` 운영자가 뺌 · `UNCHANGED` 현재와 같아 제외 · `SKU_NOT_FOUND` 상품 승인 전(→ 3단계 다시 대조) · `PRICE_REQUIRED` 가격 없음/0원 · `DUPLICATE_SKU` 같은 SKU 가 앞 줄에 있음(앞 줄만 씀).
   - `edited=true` 면 운영자가 고친 값(묶음 가격과 다름)을 표시해 준다.
   - 상단에 `submittableCount`(지금 제출하면 들어갈 줄 수)를 보여 준다.
3. **고치기** `PUT /price-import-batches/{batchId}/lines/{seedProductId}` 본문 `{basePrice?, excluded}` (헤더 `Idempotency-Key`·`X-Source-System`, `X-Expected-Version` = 배치 `version` 권장) → 배치 전체(`version` +1).
   - `basePrice` 는 1 이상 정수(0원 400). `null` 이면 묶음 값으로 되돌린다. 고친 값이 현재 가격과 같아지면 그 줄은 `UNCHANGED` 로 바뀌어 빠진다.
   - 409 `VERSION_CONFLICT` → 배치를 다시 읽고 고친다. 409 `BATCH_LOCKED` → 이미 제출됨.
   - **다시 대조** `POST /price-import-batches/{batchId}/refreshes` (헤더 `Idempotency-Key`·`X-Source-System`): 상품 승인 뒤 `SKU_NOT_FOUND` 줄을 풀거나 현재 가격을 새로 볼 때. 고친 가격·뺀 줄은 그대로 둔다.
4. **제출** `POST /price-import-batches/{batchId}/submissions` 본문 `{effectiveFrom?}` (헤더 `Idempotency-Key`·`X-Source-System`, `X-Expected-Version`) → `{batch, approvalRequest}`.
   - 효력 시각 입력: "승인 즉시"(비움) 또는 날짜·시각. 지난 시각은 422 `EFFECTIVE_FROM_IN_PAST`. 제출할 줄이 없으면 422 `NOTHING_TO_SUBMIT`.
   - 서버가 제출 직전에 다시 대조한다. 그 사이 바뀐 줄이 있을 수 있으니 응답 `batch` 로 표를 갱신한다.
   - 제출 뒤 배치는 `SUBMITTED`(잠김). 고치려면 승인을 반려하고 새로 올린다.
5. **승인 요청** — 응답 `approvalRequest` 를 **그대로** approval-service `POST /change-requests` 본문으로 보낸 뒤 기존 승인 화면 흐름(제출 → PRICE 승인). 상품 일괄 등록의 승인 요청과 같은 방식이다.
   - 승인 화면에서 행 `attributes` 의 `previousPrice`(현재 가격)·`basePrice`(새 가격)·`changeType`·`productName` 을 보여 주면 승인자가 판단하기 쉽다(BO-04 승인자가 보는 자료).
6. **결과 확인** `GET /price-import-batches/{batchId}` — 승인 결과를 받으면 `state` 가 `APPLIED`(전부 반영) / `PARTIALLY_APPLIED`(일부 줄 반영 거절) / `REJECTED`(반려), `approvalId` 가 채워진다. 제출한 줄에는 `result{state APPLIED|REJECTED, reasonCode, priceVersion}` 가 붙는다(`reasonCode` 예: `PRICE_SCHEDULE_CONFLICT` — 이미 예약된 가격 변경보다 이른 효력 시각).

## 2. 참고
- 가격 버전은 이 API 가 만들지 않는다. 승인(ApprovalDecided)으로만 생기고, 효력 시각이 되면 고객 앱 가격이 바뀐다(기존과 같음).
- 한 번에 한 SKU 씩 올리는 기존 가격 화면은 그대로 써도 된다.
- 운영자 토큰만 쓸 수 있다(고객 토큰 403).

## 3. LAN 반영
- **LAN 반영 2026-11-03 10:33 KST** — pmart-backend `b7740a6`(pricing-service, product-service `05a3b76`), pmart-config `7bcceb4`(게이트웨이 경로, 업로드 300MB, 원천 키 조회 헤더 검사 제외). api-gateway 재시작.
- 확인: 게이트웨이 경유로 실제 기준파일.zip 업로드 → 12줄 모두 `UNCHANGED`(LAN 에 이미 같은 가격이 승인돼 있음), 상품명 표시, 다시 대조, 제출 422 `NOTHING_TO_SUBMIT`. 확인용 DRAFT 배치 2개가 LAN 에 남아 있다(제출하지 않음).
- 새 가격·가격 변경 줄이 보이게 확인하려면 기준파일의 `price.basePrice` 를 바꾼 묶음을 올리거나, 아직 가격이 없는 상품을 새로 등록한 뒤 올린다.
