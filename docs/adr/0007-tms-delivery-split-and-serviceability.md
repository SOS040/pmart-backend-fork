# ADR-0007 TMS(간선)와 Delivery(고객 배송)를 분리하고 권역 매핑 정본은 delivery-serviceability에 둔다

- 상태: 제안 (2026-10-23)
- 관련: CLAUDE.md §6·§7, TMS v1.1 0.2·1.2·17.1·21, Delivery 문서, OMS 1.3·A.2.6, Commerce §1.2 L85·§5.1 L855, BackOffice BO-07

## 배경
Commerce 문서(책임 정리 이전)는 "TMS가 DC→PPC 간선과 고객 배송의 편성·배차·경로·ETA·재배차"를 맡고 "권역·담당 PPC 매핑 정본도 TMS 승인 매핑"이라고 쓴다. 2026-10-20 TMS 문서는 "고객 배송 배차·경로·ETA·라이더 수행은 Delivery"로 분리했고, BackOffice BO-07은 권역 매핑의 실행 정본을 Delivery로 둔다. CLAUDE.md도 Middle-mile TMS(5)와 Last-mile Delivery(6)를 분리했다.

## 결정
- 간선(CDC→MFC)은 `transport-*`·`fleet-service`·`cold-chain-service`. 고객 배송(MFC→고객)과 회수는 `delivery-*`·`rider-service`. 차량·기사·라이더 ID·가용성·수행료는 공유하지 않는다.
- 배송 권역 폴리곤·담당 MFC 매핑 **버전**의 정본은 `delivery-serviceability-service`. 본사(BO-07) 승인 → `ApprovalDecided` → serviceability 발효(`ServiceabilityVersionApplied`). 기존 주문은 불변.
- `fulfillment-routing-service`(1차)는 serviceability 판정 + 센터 운영 제한 조회를 묶는 얇은 계층. 다중 MFC 평가는 3차.
- Delivery 문서의 외부 브랜드 수치(1/2/3km 반경, 1분 응답, 재시도 2회·10분, 5시간 자동 완료, 신분증 확인, 안심번호 5시간, 층별 500원)는 **참고값**이며 OMS/Commerce 확정 정책과 충돌하면 확정 정책이 우선(자동 완료 없음, 주류는 PG 인증).
- Delivery 고객 배송 이벤트(`ShipmentReady`, `DeliveryAssignmentChanged`, `DeliveryCompleted`)와 TMS 간선 이벤트(`TripAssigned`, `TruckActuallyDeparted`)는 이름·토픽을 섞지 않는다.

## 결과
- Commerce 문서 §1.2·§5.1의 TMS 서술은 이 ADR로 대체. 문서 갱신 필요.
- 슬롯 정원은 별도 결정(ADR-0008).
