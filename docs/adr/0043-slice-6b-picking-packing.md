# ADR-0043 슬라이스 6b 피킹·포장 — 수동 배정, 작업자 = 운영자 역할, 피킹은 포장 구역으로, 포장 밀봉이 PACK 실적, 피킹 뒤 중지는 대기

- 상태: 승인 (2026-11-07, 사용자 "확인 미루고 구현부터 해봐" — 업무 문서 IV-07·WK-01~07·D6-1 안에서 세부 기본값은 구현자가 정하고 보고)
- 관련: ADR-0040(재고 모델 v2), ADR-0041(소비기한 보장), ADR-0042(위치 코드), `docs/business/flows/order-checkout-saga.md` D6-1·D6-2, `cancel-vs-handover.md` §1

## 배경
6a 는 작업 접수(PICK READY·PACK WAITING)까지였다. 주문이 출고 준비 완료까지 가려면 작업자 배정, 시작 점유, 피킹 실적의 재고 반영, 포장이 필요하다. 업무 문서가 정한 것: 시작 점유는 한 요청만(WK-03), 고객 물량 이동은 같은 예약에 연결하고 총량 불변(IV-07), 다른 로트 집품 차단(IV-07), 준비 시작 = 첫 PICK 시작(D6-1), 우선순위 결정(Q-08) 전에는 권한자 수동 지정만(WK-02), 피킹 시작 뒤 고객 자율 취소 없음(cancel-vs-handover §1).

## 결정
1. **배정**: mfc-capacity `POST /assignments` 수동 배정·재배정 — 그 센터 운영자 중 역할 `MFC_SUPERVISOR`. 작업마다 활성 담당 1명, `assignmentVersion` 증가, 재지정은 앞 배정 SUPERSEDED, `AssignmentPublished`(mfc.capacity.v1). 작업자 자격·근무·장비 정본이 아직 없어 WK-02 강제 제약은 검사하지 않는다.
2. **작업자 신원**: 작업자 로그인(BR-02) 결정 전 잠정으로 운영자 계정 + 역할 `MFC_WORKER` + 센터 권한. workerId = 운영자 ID.
3. **시작**: fulfillment 가 capacity 배정을 동기 조회(작업자 토큰 전달, 1 hop). 실패면 503·기록 없음. 판정(STARTED/REJECTED)을 기록하고 같은 키는 기록된 판정. 그 주문의 첫 PICK 시작에 `PreparationStarted` → order-service 가 CONFIRMED → PREPARING·`OrderPreparationStarted`(이후 고객 자율 취소는 claim 심사에서 막힌다).
4. **피킹 실적**: 한 실행 = 한 선반. inventory `PICK` 이 선반 NORMAL·STORED → **포장 구역(PACKING) 위치**의 PICKED 로 옮기고 예약 조각의 집품량을 늘린다(총량 불변). 줄은 예약에 있는 LOT 만(LOT_MISMATCH), 남은 양 안에서만. 접수 → 재고 반영(트랜잭션 밖, 같은 실행 ID 는 한 번) → APPLIED/REJECTED/UNKNOWN_RESULT. 포장 위치의 온도대는 보지 않는다(짧게 머무는 작업 위치).
5. **결품 보고**: 작업을 BLOCKED 로 두고 `LineShortageReported` 만 낸다. 재고(격리·BLOCKED 예약·이행부족)와 고객 결정은 IV-09 단계에서 — 그때 ADR-0042 결정 1(같은 SKU 적격 LOT 대체)을 쓴다.
6. **포장**: PACK 실적은 포장 밀봉(`POST /shipments/{id}/packages`)으로만 — `executions` 의 PACK 은 422. LOT 은 요청에 없으면 집품했지만 아직 포장하지 않은 조각에서 소비기한 빠른 순. inventory `PACK`(PICKED → PACKED)이 반영돼야 SEALED + QR(`PKG:<id>:<버전>`). 출고의 집품분이 모두 포장되면 PACK COMPLETED·출고 READY·`PackingFinished`·`ShipmentReady`.
7. **피킹 뒤 중지**: 피킹이 시작된 주문의 작업 중지 요청은 작업을 지우지 않고 `PENDING`(S3 는 대기·재조회). 집품분의 판매 제외·복귀(IV-08)와 포장별 판정이 생길 때 판정한다. inventory 도 집품이 시작된 예약의 단순 해제를 409 `RESERVATION_PICKED` 로 막는다(R 만 줄이는 오류 방지).

## 대안
- 시작 때 배정 투영만 믿기: 투영이 늦으면 담당이 바뀐 작업을 옛 담당이 시작한다. 계약대로 동기 조회.
- PACK 을 실행·밀봉 두 단계로: 현장 입력이 두 번이 된다. 밀봉 하나로.
- 피킹 뒤 중지를 STOPPED 로 처리: 집품분이 판매 가능 재고로 남은 채 예약만 풀린다. 택하지 않음.

## 결과
- 계약: mfc-capacity 0.2.0, mfc-inventory 0.5.0, mfc-fulfillment 0.2.0. 마이그레이션: capacity V3, inventory V7, fulfillment V4.
- 구독 추가: fulfillment ← `AssignmentPublished`(mfc.capacity.v1, `assignmentEvents-in-0`), order ← `PreparationStarted`(mfc.fulfillment.v1, `fulfillmentEvents-in-0`).
- 남은 것: 배차·인계 fence(6c, IV-08), 결품·격리 처리(IV-09), 재포장, 작업자 로그인(BR-02), 단말 바코드 대조(상품 바코드 사본 없음 — TaskLine.barcode 는 null).
