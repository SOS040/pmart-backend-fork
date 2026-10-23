# ADR-0002 재고 원장은 센터 유형별로 하나의 서비스만 쓴다 (cdc-inventory, mfc-inventory)

- 상태: 제안 (2026-10-23)
- 관련: CLAUDE.md L62·L68·L74·L83, WMS v8.1 0.1·L774·L1357, MFC IF-06 L1816·IV-08 L1425, SCM 1장, TMS 12.1

## 배경
WMS v8.1과 MFC v2.0은 "별도 MFC용 WMS를 두지 않는다. CDC WMS는 CDC 재고·TOUT·Transit, MFC는 자기 센터 수량을 소유한다"로 책임을 정리했다. 문서들은 공통으로 "재고 수량을 바꾸는 주체는 하나"를 요구하고, 적치 완료·인계 확정 같은 사건은 여러 수량(현물·예약·공간)을 **한 거래**로 바꿔야 한다.

## 결정
1. `cdc-inventory-service`만 CDC 수량(위치·LOT·품질상태별 On-hand, 이관 예약 R, Held, Transit)을 변경한다. TOUT은 `현물−q·예약−q·Transit+q` 한 거래. Transit 대사(`q = r + l + t`)도 여기서.
2. `mfc-inventory-service`만 MFC 수량(H/Q/E/R/A, 위치·LOT·품질·업무상태별 Balance, ReservationPart)을 변경한다. 인계 확정은 `H−q·E−q·R−q` 한 사건, TIN은 `H+r`.
3. 원자성이 필요한 보조 수량은 같은 서비스에 둔다: MFC 공간 잔여량 V(MoveAllocation)는 `mfc-inventory`가 보유(IV-03/04). CDC 공간 용량 예약은 `cdc-location`에 두되 적치 완료는 "location 선점 → inventory 반영 → 작업 완료" 사가로 처리(S7).
4. inbound/outbound/quality/fulfillment는 수량 변경을 **요청**하고 `commitId`를 받는다. 자체 재고 사본으로 성공을 판단하지 않는다.
5. MFC TIN은 CDC Transit을 직접 줄이지 않는다. `MfcReceiptPosted`를 받은 `cdc-inventory`가 같은 receiptId로 1회 대사한다. 전체 현물 = `CDC H + Σ MFC H + Transit`은 읽기 전용 대사 보고서로만 계산.

## 대안
- 단일 `inventory-service`로 CDC+MFC 통합 — 문서 책임 정리(2026-10-20)에 반하며, 센터별 독립 배포·장애 격리가 깨진다.
- MFC별 WMS 인스턴스 — 문서가 명시적으로 배제.

## 결과
- 두 원장 사이의 수량 이동은 **항상 이벤트**(`TransferDeparted`, `MfcReceiptPosted`, `TransitReconciled`)이며 중복 계상 방지는 receiptId/departureId 유일성으로.
- CLAUDE.md L62 "LOT별 잔량"은 "위치·LOT·품질상태별"로 읽는다(WMS F-011/029 성립 조건).
- LPN(BOX/PLT) 구성 정본은 `cdc-outbound`로 둔다 `[결정 필요]`.
