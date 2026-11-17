# cart-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cart-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cart-service.yaml` → `openApiGenerate` 가 `com.pmart.cart.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.cart.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:cart-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:cart-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8104.

## 장바구니 (2026-10-30, ADR-0022)
- 주인 = 토큰 주체: 고객 토큰 → MEMBER(accountId), 비회원 토큰(typ=GUEST, auth guest-sessions) → GUEST. 운영자·기사는 403. 남의 장바구니 403 `FORBIDDEN_OWNER`. guest-paths 는 `/api/v1/carts/**`.
- 진행 중 장바구니는 주체마다 하나. 마지막 변경 후 `pmart.cart.retention`(30일)이 지나면 EXPIRED → POST /carts 가 새로 만든다(만료 장바구니 접근은 404 `CART_EXPIRED`).
- 수량은 절대값. Idempotency-Key + expectedVersion(본문, 헤더와 같아야 함). 버전 다르면 409 `CART_VERSION_CONFLICT`. 같은 키 재전송은 저장 결과(`cart_change`)를 돌려준다.
- 검증 순서: 판매 상태(product `/internal/skus`) → 구매 상한(product `/internal/purchase-limit-checks`, 장바구니 전체). 가용은 담기를 막지 않는다(Commerce 7장). 검증 단계 하위 장애는 반영 전이라 DEPENDENCY_UNAVAILABLE.
- product·mfc-inventory 내부 호출은 토큰 없이(ADR-0026). 콘텐츠 카드·가격은 공개 API.
- 표시 가용(2026-10-31; 2026-11-18 계약 0.1.1 — 값은 `shippableAvailable`, 오늘 기준·deliveryDate 없이, null 은 UNKNOWN): 장바구니 mfcId(열 때 앱이 준 쇼핑 MFC) 기준 mfc-inventory `/internal/availability` 사본 → `CartRules.availability` — 취급 OFF NOT_CARRIED, A≤0 SOLD_OUT, A<담은 수량 LOW(availableQty 공개), 그 밖 AVAILABLE(수량 비공개, 잔여 수량 노출 임계값은 운영 설정이라 미사용). mfcId 없음·조회 실패는 UNKNOWN(+ sources UNAVAILABLE).
- 조회 화면의 이름·이미지·가격·판매 상태는 사본 — 출처마다 실패하면 비우고 `sources[].asOf.freshness=UNAVAILABLE`. 가격 모르는 선택 품목이 있으면 `selectedItemAmount` null.
- 병합: 회원 토큰 + `X-Guest-Token`(비회원 access token, JWKS 검증)으로 원본 소유 증명. 항목별 재검증(원본 그대로·판매 상태·상한 누적), 성공분만 대상에 더하고 원본에서 뺀다(한 트랜잭션). CONFIRMED/PARTIAL/REJECTED, mergeId 는 (회원, 키)에서 결정적.
- 사건: `CartItemChangeApplied`(토픽 `commerce.cart.v1` 명시, 담기·변경·삭제·주문 정리). 병합은 아직 사건을 내지 않는다.
- 구독 `OrderConfirmed`(order.order.v1, InboxGuard, 2026-10-31): 주문 계정의 진행 중 장바구니에서 주문분만 뺀다 — 줄의 cartItemId(없을 때만 SKU)로 찾고 주문 수량만큼 감소(`CartRules.remainingAfterOrder`, 0이면 삭제). 품목마다 cartVersion +1·`CartItemChangeApplied`(appliedRequestId `OrderConfirmed:<orderId>`). cart_change 는 남기지 않는다. ProductSaleStateChanged 구독은 아직.

## 상태
장바구니 열기·조회·담기·수량/선택 변경·삭제·변경 결과 조회·병합·주문용 내부 조회 구현(2026-10-30), 주문 확정 후 정리·표시 가용(2026-10-31). 병합 때 가용 재검증은 아직.
