# [pmart-backend → pmart-clients] 회신: BR-40 통합 승인함 · BR-41 제출한 배치의 승인 요청 · BR-42/43 장바구니 (2026-11-03)

- 요청: `pmart-clients/docs/handoff/clients/2026-11-03-backend-approval-inbox-and-batch-approval.md`(BR-40·41), `2026-11-03-backend-cart-latency.md`(BR-42·43).
- 계약: `backoffice-bff.yaml`(승인함 항목 필드 추가), `approval-service.yaml`(`targetRef` 필터), `product-service.yaml`·`product-content-service.yaml`(`pendingSubmission`), `pricing-service.yaml`(`approvalRequest`), `cart-service.yaml`(`CartChangeResult.cart`). 모두 **필드·파라미터 추가**만이다.
- LAN: 배포 시점은 따로 안내한다.

## 1. BR-40 통합 승인함(bff) — 구현 (요청 (a))
`GET /api/v1/backoffice/views/approval-inbox?approvalType=&reviewState=&centerId=&size=&cursor=`
- approval-service `GET /approvals` 를 **로그인한 운영자의 토큰 그대로** 읽어 묶는다. 정렬은 제출 최신순, `page.nextCursor` 로 다음 쪽.
- **`dossierId` = approval-service 승인 ID**(같은 값을 `approvalId` 로도 준다). 상세·결정은 지금처럼 `/approvals/{approvalId}`.
- 항목: `approvalId`·`changeRequestId`·`approvalType`·`targetService`·`targetRef`·`summary`·`requestState`·`reviewState`·`executionState`·`deliveryState`·`requestedBy`·`caseId`·`submittedAt`·`expiresAt`·`source`(DIRECT).
- approval-service 가 응답하지 않으면 **200 + 빈 `items` + `unavailableSources: ["approval-service"]`** — "승인 건 없음"과 구분해 "원천 조회 실패"로 보여 달라. 운영자가 아니면 403.
- `centerId` 는 approval-service 가 아직 거르지 않는다(넘기기만 함).

## 2. BR-41 제출한 배치의 승인 요청 다시 읽기 (요청 (a) + 승인 ID 찾기)
- 배치가 **승인 대기(SUBMITTED)** 인 동안 조회 응답에 제출 결과가 다시 온다 — 잠긴 행으로 제출 응답과 **같은 본문**을 만든다. sessionStorage 없이 이 값을 쓰면 된다.
  - 상품 `GET /product-import-batches/{batchId}` → `pendingSubmission{submittedDraftIds, excludedDraftIds, approvalRequest}`
  - 콘텐츠 `GET /content-import-batches/{batchId}` → `pendingSubmission{…}`
  - 가격 `GET /price-import-batches/{batchId}` → `approvalRequest`
  - 승인 결과가 반영되면(APPLIED·REJECTED 등) 이 필드는 없어진다.
- **승인 ID 찾기**: approval-service `GET /approvals?targetRef=productImportBatch:{batchId}`(콘텐츠 `contentImportBatch:`, 가격 `priceImportBatch:`) — 정확히 일치하는 승인만 온다. 50건 제한 없이 그 배치의 승인을 바로 찾는다. 결과가 비어 있으면 아직 승인 요청을 올리지 않은 것 → `pendingSubmission.approvalRequest` 로 올린다.

## 3. BR-42 장바구니 표시용 조회
- 시간 제한은 **이미 있다**: 서비스 간 호출 공통 기본값 연결 1초·응답 5초(`common-web`). 요청서의 "timeout 없음"은 정정한다 — PC3 가 꺼졌을 때 한 번 조회가 최대 몇 초 걸린 것은 이 값 안이다.
- **순차 → 동시**로 바꿨다: product·content·pricing·mfc-inventory 네 곳을 동시에 불러, 걸리는 시간이 합이 아니라 가장 느린 하나다. `sources[]` 순서·실패 표시는 그대로.
- "담을 때 이름·단가 사본 저장"은 이번에 하지 않았다(사본이 낡는 문제·정본 원칙 검토가 필요).

## 4. BR-43 변경 응답에 갱신된 장바구니 — 구현 (요청 (a), 권장안)
- 담기·수량/선택 변경·삭제 응답(`CartChangeResult`)에 **`cart`**(= `GET /carts/{cartId}` 와 같은 모양: 품목·`display`·`selectedItemAmount`·`sources`)가 들어간다. 앱은 이 값으로 화면을 끝내고 두 번째 GET 을 하지 않아도 된다(합계 "확인 중" 단계도 필요 없다).
- 결과 조회 `GET /carts/{cartId}/change-requests/{requestId}` 도 `cart` 를 주지만 **지금의 장바구니**다 — 그 변경 직후 상태가 아닐 수 있으니 `cartVersion` 으로 비교.
