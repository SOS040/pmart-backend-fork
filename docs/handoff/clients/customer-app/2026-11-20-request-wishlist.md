# 요청 — 고객 앱 찜(하트) (customer 0.13.0, ADR-0081)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-20
- 근거: 사용자 "찜 기능 넣고 싶어" — 결정: 회원만, 상품 단위, 최대 200개, 품절·판매 종료도 남기고 표시, customer-service
- 정본: `docs/adr/0081-customer-wishlist.md`, 계약 `contracts/openapi/customer-service.yaml` 0.13.0(태그 `wishlist`)
- 상태: 아래 "배포 상태" 줄

## API (모두 고객 토큰 — 비회원·직원 403)
- `PUT /api/v1/me/wishlist-items/{productId}` — 찜하기. 헤더 `Idempotency-Key`·`X-Source-System`. 200 `{productId, addedAt, total}`. 이미 찜했으면 그대로 200(처음 시각).
  - 422 `WISHLIST_LIMIT_EXCEEDED`(details `limit` — 지금 200) → "찜은 200개까지 할 수 있어요", 409 `INVALID_STATE`(탈퇴 신청 이후).
- `DELETE /api/v1/me/wishlist-items/{productId}` — 빼기. 같은 헤더. 늘 204(없어도).
- `GET /api/v1/me/wishlist-product-ids` — `{productIds[], total}` 최근 찜한 순. **하트 표시용** — 로그인·앱 시작 때 한 번 받아 두고 찜/빼기 때 로컬로 고친다(목록 카드마다 묻지 않기).
- `GET /api/v1/me/wishlist-items?page=&size=`(size ≤50, 기본 20) — `{items[], total, limit}`. 항목 `productId·addedAt·name·imageUrl(?w= 축소)·price(대표 기본가)·saleState`.
  - `saleState`: `ON_SALE`(살 수 있음) · `SOLD_OUT`(품절 — 지금 쇼핑 주소 센터 기준) · `ENDED`(판매 종료) · `UNKNOWN`(정보 없음 — 상품 정보를 못 읽음).
  - 품절·판매 종료도 목록에 남는다(흐리게 + "품절"/"판매 종료", 담기 숨김). `UNKNOWN`·이름 null 이면 "상품 정보를 불러오지 못했어요"(지어내지 않기).

## 화면
- 상품 상세·목록 카드(검색·홈·카테고리)에 하트. 비회원이 누르면 로그인 안내(서버는 비회원 찜이 없다).
- 마이 → "찜한 상품" 목록(최근 순, 무한 스크롤). 항목 누르면 상품 상세, 하트로 빼기.
- 재입고 알림은 이번 범위가 아니다(누르면 알림 약속 문구 쓰지 않기).
- 탈퇴하면 찜도 지워진다(탈퇴 안내 화면 "지워지는 것"에 찜 추가).

## 배포 상태
- (작성 시점) 서버 구현·시험 끝, LAN 배포 전 — 배포하면 이 줄을 고친다.
