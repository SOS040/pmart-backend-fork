# [pmart-backend → pmart-clients] ② 비회원 둘러보기 공개 경로·시험 데이터 안내 (2026-10-30)

- 관련: `2026-10-30-clients-reply-customer-app-priority.md` 의 ②, `2026-10-30-clients-notice-customer-addresses.md`(①)

## 1. 토큰 없이 부를 수 있는 조회 (계약 `security: []`)
| 화면 | API |
|---|---|
| 카테고리 트리 | `GET /api/v1/categories/tree` |
| 카테고리 필터 패널 | `GET /api/v1/categories/{categoryId}/filters` |
| 상품 상세 | `GET /api/v1/product-contents/{productId}` |
| 상품 카드 일괄(최대 100) | `GET /api/v1/product-contents?productIds=a,b,c` |
| 상품 이미지 | `GET /api/v1/content-images/{imageId}` — **게시된 이미지는 `<img src>` 로 바로 쓸 수 있다**(응답의 `url` 은 `/api/v1/content-images/...` 상대 경로라 게이트웨이 주소를 앞에 붙인다) |
| 표시 가격 | `GET /api/v1/prices/{sku}`, `GET /api/v1/prices?skus=a,b` |
| 배송 가능 지역 | `POST /api/v1/serviceability-checks` (① 에서 공개) |

- 게시·발효된 것만 보인다. 로그인한 고객도 같은 API 를 토큰과 함께 부르면 된다.
- 가격이 없으면 404 `PRICE_NOT_SET` — 0원이 아니라 "판매 불가"로 표시.

> **LAN 반영됨**(2026-10-30, 이미지 e05990a): 게이트웨이 `http://192.168.0.40:8080` 에서 토큰 없이 카테고리 트리·필터·상품 상세·카드·가격 200, 게시 이미지 200(image/png) 확인. 비공개 경로(예 `/products`)는 그대로 401.

## 2. LAN 시험 데이터 (적재 완료)
- 카테고리 트리 v1: 신선식품(채소, 두부·계란) / 간편식(국·탕·찌개, 밀키트·냉동) / 생활용품(휴지·물티슈). id `t01`, `t01-c01` …
- 상품 6개(상품명 `[시험] …`, 대표 이미지는 합성 그림):

| SKU | 상품 | 기본가 | 카테고리 |
|---|---|---|---|
| E-00000001 | [시험] 국산콩 두부 300g | 1,900 | t01-c02 |
| E-00000002 | [시험] 친환경 애호박 1개 | 1,500 | t01-c01 |
| E-00000003 | [시험] 소고기 미역국 500g | 4,900 | t02-c01 |
| E-00000004 | [시험] 차돌 된장찌개 밀키트 2인분 | 8,900 | t02-c02 |
| E-00000005 | [시험] 고기 왕만두 1kg | 7,900 | t02-c02 |
| B-00000006 | [시험] 3겹 화장지 30롤 | 12,900 | t03-c01 |

- productId 는 `GET /product-contents?productIds=` 에 넣을 값이다. 홈·검색(③)이 생기기 전에는 아래 값을 직접 쓴다:
  `PRD-b962c916-3960-495c-8f72-c510cd59fbc6`, `PRD-d395208b-1bf1-4c1b-ad16-021764e14b0e`, `PRD-2bb767d9-c983-4345-b6d5-f1a2729e7b00`, `PRD-cbfad89e-ca4f-432e-bba6-5fa4f98e1ce4`, `PRD-4bfeb506-40e5-43d6-bf20-abe4551fa226`, `PRD-0737e3fa-2412-42d8-8289-95c132b7156d`
- 담당 센터 `MFC-SEED-01`: 6개 SKU 취급 ON(재고는 아직 미구현).
- 배송 권역 v1 = 서울 서초·강남 일부(경도 127.00~127.06, 위도 37.47~37.53).
  - 배송 가능 예: 서초역 부근 `lat 37.4918, lng 127.0076` → `SERVICEABLE`, `MFC-SEED-01`
  - 배송 불가 예: 광화문 부근 `lat 37.5759, lng 126.9768` → `OUT_OF_AREA`
  - ① 의 주소 추가·쇼핑 주소 선택에 위 좌표를 넣으면 SERVICEABLE 로 나온다.
- 백오피스 시험 계정 `seed-md`(MD)·`seed-approver`(승인권자)가 생겼다. 비밀번호는 seed 실행마다 바뀌므로 백오피스 로그인에는 관리자가 만든 별도 계정을 쓴다.
- 데이터를 다시 넣거나 늘릴 때: 백엔드 `deploy/lan/seed/`(승인 API 경로로만 적재).
