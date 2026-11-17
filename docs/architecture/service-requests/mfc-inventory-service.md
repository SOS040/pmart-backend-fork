# mfc-inventory-service 에 대한 요청

받는 영역: mfc(PC 3). 요청한 쪽이 지금 어떻게 버티는지와 생기면 바꿀 것을 같이 적는다.

## IV-1. 가용 수량에서 출하 부적격 LOT 빼기 — 높음 (2026-11-16, 보낸 영역 platform — 고객 주문 막힘)
- **본 것(LAN 2026-11-16)**: 고객이 6개 상품을 주문 → 주문서까지 들어감 → 결제 단계 재고 예약(`RESERVE_INVENTORY`)이 `INSUFFICIENT_STOCK`("2개 줄에 소비기한 기준을 통과한 재고가 부족합니다")로 거절. 원인 두 상품: `E-00000496` 재고 5개가 모두 소비기한 11-17(최소 잔여일 미달), `E-00000501` 남은 1개가 소비기한 11-13(**이미 지남** — 그래도 `sellable 1` 로 보임). 센터 전체 `GET /mfcs/MFC-SEED-01/unshippable-lots` = 250 LOT·135 SKU·1,751개, 그중 이미 지난 것 65 LOT·48 SKU.
- **문제**: `GET /internal/availability`(장바구니·주문이 쓰는 가용 A = E − R)에 **소비기한 기준을 통과하지 못한 LOT 이 들어 있다**(계약 `Availability` 설명에도 "예약이 NO_ELIGIBLE_LOT 으로 거절될 수 있다"). 그래서 장바구니는 "5개 있음"으로 보이고 주문서까지 가서 결제 단계에서야 막힌다. 사용자 결정(2026-11-16): **재고가 없으면 주문서 화면으로 가면 안 된다.**
- **할 것**
  1. 가용 조회에 **출하 가능 수량**을 더한다 — 예약과 같은 적격 판정(상품 소비기한 보장, 없으면 보관 유형 최소 잔여일, 기준일 = 오늘 또는 요청한 배송 약속일)을 통과한 LOT 의 E − R. 새 칸(예: `shippableAvailable`, 필드 추가 — 하위 호환)으로 두거나 `available` 을 그 값으로 바꾼다(바꾸면 계약 버전과 cart 영향 표시). 배송 약속일 질의(`deliveryDate=`)가 있으면 예약 배송도 맞게 판정된다.
  2. **소비기한이 지난 LOT 이 `sellable` 로 남아 있는 것** 확인 — 지난 LOT 은 출하 부적격으로 빠져야 한다(지금은 E 에 그대로 셈).
- **요청한 쪽에서 바꿀 것(PC 1)** — order 쪽은 2026-11-16 구현(계약 0.8.0): order-service 주문 초안 생성 때 이 가용을 확인해 부족하면 초안을 만들지 않는다(422, 부족 상품·수량) — 1 이 생기기 전에는 `available` 로 확인한다(그래서 소비기한 미달은 아직 결제 단계에서 걸린다). cart 표시도 새 값으로.
- **사용자 결정**: 필요 없음(재고 없으면 주문서 진입 금지 — 사용자 2026-11-16, 적격 기준은 이미 있는 예약 규칙).

## IV-2. PDA "재고 확인" — 상품명 (2026-11-17, mfc-app 요청 전달 — platform PC 1)
- 원 요청: pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-pda-stock-check.md`(PC 3 mfc-app, 사용자 결정 "재고 확인" 메뉴).
- Q1 답(PC 1 조사 2026-11-17, `docs/architecture/auth/permissions.md` §8): `GET /mfcs/{mfcId}/stock` 은 **이미 그 센터 직원이면 역할 없이** 된다(`CorrectionService` — OPERATOR + centers). MFC_WORKER 자기 센터 토큰 OK, 바꿀 것 없음. 계약 설명에 "그 센터 운영자(역할 무관)"를 적어 두면 좋다.
- **요청 Q4**: 응답 줄에 `productName`(R19-1 이동·실사처럼 SKU 사본의 이름) — PC 3.
- Q3(칸으로 재고 찾기)는 기존 MB-1 과 같다.

## IV-3. 피킹 도착지 = 장바구니, 포장 = 장바구니 → 패킹대 (2026-11-17, mfc-app 요청 전달 — platform PC 1)
- 내용·판정은 `mfc-fulfillment-service.md` **F-6**(P1·P2) — inventory `TaskExecutionService` PICK 의 `requirePacking` → `requireCart`, PACK 은 장바구니 → 패킹대. 원 요청 pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-pick-to-cart.md`.

## AP-1 참조. 승인 번호 실제 확인 — 본사 승인 기준·한 번만 쓰기 (2026-11-17, platform PC 1)
- 요청 본문은 `approval-service.md` **AP-1 "PC 3 이 할 것" 1~6**(이 서비스 몫: 실사 조정·재고 정정 — `CountService` 승인, `CorrectionService`). 기준(사용자 2026-11-17): 한 건 손실 10만 원 이상 또는 한 SKU 20개 이상이면 본사 승인, 그 아래는 센터 관리자. approval-service 쪽(승인 종류·`/internal/approval-ref-checks`·`/internal/approval-ref-uses`)은 구현됨(b969005d).
