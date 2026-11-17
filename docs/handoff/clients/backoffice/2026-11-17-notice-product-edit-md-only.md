# [pmart-backend → pmart-clients] 알림: 상품 정보 수정 — MD 만·LAN 배포됨·예전 경로도 같은 규칙 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 받는 앱: backoffice(`web/backoffice` 상품 관리)
- 앞 요청: `2026-11-17-request-product-edit.md`(00:45, 계약 0.3.0, "LAN 배포 전") — 그 뒤 바뀐 것만 적는다. 계약 `product-service.yaml` **0.3.2**.

## 1. 수정 시작은 MD 만 (사용자 결정 2026-11-17 "md")
- `POST /api/v1/products/{productId}/edit-drafts` — 토큰 역할에 **`MD`** 가 없으면 **403 `FORBIDDEN`**("상품 정보 수정 시작 권한(MD)이 없습니다.").
- 화면: 상품 상세의 **"정보 수정" 버튼은 세션 역할에 `MD` 가 있을 때만** 보인다(판매 상태 변경 버튼과 같은 기준). 판정은 서버 — 403 이 오면 "MD 만 수정을 시작할 수 있어요" 안내.
- 이미 만들어진 수정 초안을 **고치기(PUT)·제출은 예전처럼 운영자 누구나**(MD 아니어도 됨).

## 2. 예전 경로도 같은 규칙 (0.3.1·0.3.2)
초안 만들기 `POST /product-drafts`·고치기 `PUT /product-drafts/{draftId}` 에 `targetProductId` 를 직접 넣는 경로도:
- **MD 만**: `targetProductId` 를 넣어 새로 만들거나, PUT 으로 `targetProductId` 를 **새로 정하거나 바꾸면** MD 가 아니면 403. 같은 값을 유지한 PUT 은 누구나.
- **열린 수정 초안은 상품당 하나**: 그 상품을 가리키는 다른 열린(DRAFT·SUBMITTED) 초안이 있으면 **409 `EDIT_DRAFT_EXISTS`**(`details[0].reason` = 그 draftId) — 수정 시작과 같은 처리(그 초안으로 이동).
- 화면이 "수정 시작" 경로만 쓰면 따로 할 일은 없다. **신규 상품 등록 폼에서 `targetProductId` 를 넣는 곳이 있으면 빼거나 MD 에게만** 보인다.

## 3. LAN 배포됨
- PC 2 product-service(2026-11-17, 이미지 9b44d0a2 계열) — 확인: MD 아닌 `claude-dev` 로 수정 시작 → 403, 예전 경로 + targetProductId → 403.
- 시험 계정: MD 역할 계정으로 시험한다(`docs/operations/lan-test-accounts.md` — MD 가 필요하면 PC 1 에 요청, 관리 경로 역할 맞춤은 LAN 만 가능).

## 4. 함께 바뀐 조회 권한(화면 영향 없음 — 참고)
상품 목록·상세·SKU·바코드·판매 상태/SKU 발급 결과 조회, 가격 버전·반영 결과, 승인 요청 조회, 센터·캘린더 조회가 **서비스 안에서도 직원 토큰만**(고객·라이더 토큰 403). 백오피스는 직원 토큰이라 그대로 된다(`docs/architecture/auth/permissions.md` §10-1).

## 회신
화면 반영 뒤 `docs/handoff/clients/backoffice/` 회신 또는 PR 본문으로.
