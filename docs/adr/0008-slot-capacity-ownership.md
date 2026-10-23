# ADR-0008 고객 도착 슬롯은 delivery-capacity, 작업 여력은 mfc-capacity가 소유하고 결제 전 둘 다 확보한다

- 상태: 제안 (2026-10-23) — 운영값 `[결정 필요]`
- 관련: CLAUDE.md L78 `mfc-capacity`·L102 `delivery-capacity`, OMS 6·6.1·A.2.5, MFC WK-13·IV-05 L1362·Q-18, Delivery 10.3

## 배경
OMS 문서는 "슬롯 정원·점유의 정본은 MFC Operations"이면서 "정원은 MFC 작업·패킹·보관 여력과 Delivery의 고객 배송 가능 조건 중 승인된 조건을 만족해야 한다"고 쓴다. MFC 문서는 CapacityHold(작업 여력)가 재고 R과 독립이라고 하며, 센터/배송 슬롯 정본은 Q-18 미결이다. CLAUDE.md는 `mfc-capacity-service`(작업 슬롯)와 `delivery-capacity-service`(배송 슬롯)로 나눴다.

## 결정
1. **고객이 선택하는 도착 시간대(arrival window)와 그 정원·점유의 정본 = `delivery-capacity-service`.** 주문당 슬롯 1건(분할배송도 1건), 마지막 정원 1주문, HELD→CONFIRMED/RELEASED/EXPIRED.
2. **MFC 작업 여력(피킹·패킹대·보관·인력 환산)과 CapacityHold의 정본 = `mfc-capacity-service`.**
3. 결제 진입 전 orchestration(S1)이 `mfc-inventory.Reserve` → `mfc-capacity.Hold` → `delivery-capacity.HoldSlot` 순으로 **모두** 확보한다. 재고가 충분해도 여력·슬롯이 없으면 접수 실패(IV-05).
4. 슬롯 정원 값은 승인 입력(1차 수동 설정). `mfc-capacity`의 `CapacityReduced`와 라이더 수급(`RiderSupplyChanged`)은 delivery-capacity의 **입력**이지 자동 정원 변경이 아니다. 정원 축소 시 기존 약속은 삭제하지 않고 영향 주문을 조사한다.
5. 조회 실패·미설정은 정원 0/무제한이 아니라 `CONFIG_REQUIRED`로 해당 신규 결제를 보류.
6. 예약 시간 변경(S4)은 새 슬롯 임시 확보 → 준비 시작 경합 판정 → 성공 시 기존 슬롯 해제, 실패 시 새 슬롯만 해제.

## 대안
- 슬롯 정본을 mfc-capacity 하나로 — 라이더 수급 조건을 MFC가 알아야 하고 Delivery 도메인 침범.
- 슬롯을 order-service가 소유 — 정원은 운영 자원이므로 주문 서비스가 가질 수 없음.

## 결과
- 결정 필요: 08시/09시 개시 차이, 시간대별 정원 산정식, 예약 유효시간(MFC Q-04: 10분 미채택 표기 vs OMS 10분 확정) — 본 설계는 OMS `[확정 정책]` 10분을 따른다.
