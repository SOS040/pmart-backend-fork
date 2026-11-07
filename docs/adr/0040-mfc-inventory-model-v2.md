# ADR-0040 MFC 재고 모델 v2 — 위치·LOT·품질·업무상태별 잔량, LOT 단위 예약(FEFO·최소 잔여일), 단계별 검수·적치

- 상태: 승인 (2026-11-07, 사용자 "그대로 일단 진행" — MFC 를 업무 문서대로 다시 만든다)
- 대체: 슬라이스 4 의 간략 모델(V3 주석 "ReservationPart 의 최소형", 센터·SKU 한 행). ADR-0002(단일 쓰기 주체)는 그대로.
- 정본 규칙: `docs/business/MFC-business-logic.md` §3 재고관리(IV-01~13·상태 축·§6 허용 전이), service-map §5 mfc-inventory, 계약 `contracts/openapi/mfc-inventory-service.yaml`
- 결정 기록: MFC 문서 §6 Q-05(2026-11-07, 아래 결정 5)

## 배경
- 계약은 처음부터 Balance(센터·위치·SKU·LOT·품질·업무상태), ReservationPart(HELD/CONFIRMED/RELEASED/EXPIRED/BLOCKED/CONSUMED), 검수·적치·피킹·인계 전기를 적었지만, 슬라이스 4 구현은 센터·SKU 한 행(H/Q/E/R)과 SKU 합산 예약만 만들었다. 수령하면 바로 판매 가능, LOT·위치·기한 없음.
- 이 상태로는 FEFO, 소비기한 차단, 격리 → BLOCKED → 이행부족(D), 피킹 위치, 6a 재시도(같은 MFC 재확보)가 실제로 성립하지 않는다.
- SCM·CDC 출고·CDC 품질·TMS 는 서비스가 아직 없다. MFC 안은 업무 문서대로 만들고, 없는 상대는 계약 모양의 대체 입력(MFC 밖, `source_system` 표시)으로 채운다. 미결정 값은 문서의 보류 동작(`CONFIG_REQUIRED`·연계 대기)으로 둔다.

## 결정

### 1. 잔량: 단위 행 + 두 요약
- **`stock_unit`** — 정본 잔량. 키 `(mfc_id, location_code, sku, lot_id, quality_state, work_state)`, `qty ≥ 0`, `row_version`.
  - quality_state: `PENDING_INSPECTION / NORMAL / QUARANTINED / DISPOSAL_PENDING`
  - work_state: `UNSTOWED / STORED / PICKED / PACKED / CANCEL_RETURN_PENDING`
  - 예약 관계는 단위 행의 상태가 아니다(`RESERVED` 현물 상태 없음, §2 상태 축).
- **`stock_lot_balance`** `(mfc_id, sku, lot_id)` 와 **`stock_balance`** `(mfc_id, sku)` — 같은 트랜잭션에서 함께 바꾸는 요약. H/Q/E/R 과 `0≤R≤E≤H`, `0≤Q≤H` 를 표 CHECK 로 막는다. LOT 요약은 예약 배정(FEFO), 센터 요약은 가용 조회·장바구니가 읽는다(기존 응답 모양 유지).
- 수량 기호와 단위 행의 대응:
  - H = 모든 단위 행의 합
  - Q = `QUARANTINED + DISPOSAL_PENDING`
  - E = `NORMAL` 이고 work_state 가 `STORED/PICKED/PACKED` 인 행(검수 대기·적치 대기·취소 복귀 대기는 E 밖)
  - R = 예약 조각 중 현물(HAND) `HELD + CONFIRMED`
  - 실사 잠금·내부 이동 배정의 E 제한은 IV-03·IV-11 구현 때 더한다.
- 잠금 순서: 센터 요약(SKU 오름차순) → LOT 요약 → 단위 행. 모든 변경이 같은 순서로 잠근다.

### 2. LOT
- **`stock_lot`** `(lot_id)`: sku, 제조 LOT 원문(있으면 그대로), 소비기한(날짜, 없을 수 있음), 추적 상태 `TRACKED / UNTRACKED`.
- 내부 `lot_id` 는 inventory 가 발급한다(불투명 ID `LOT-<ULID>`). WMS 문서의 `LOT-8자리` 는 제안 형식이라 쓰지 않고, 코드에서 날짜를 추정하지 않는다.
- 같은 SKU·제조 LOT 원문·소비기한이면 같은 `lot_id`. 원문이 없는 수령은 수령마다 `UNTRACKED` LOT 하나.
- 센터별 **최초 정상 인수 시각**(FEFO 동률 기준)은 `stock_lot_balance.first_normal_at` 에 둔다. 이동·같은 LOT 추가 수령으로 바꾸지 않는다.

### 3. 수령·검수·적치 (IV-02·03)
- `ConfirmReceipt`: 수령 위치(`toLocationCode`)에 `UNSTOWED` 로 들어간다. 정상은 `PENDING_INSPECTION`, 격리는 `QUARANTINED`, 미판정은 `PENDING_INSPECTION`(보류 사유 기록). **H 만 증가, E 증가 없음.**
- `CommitTaskExecution INSPECT`: `PENDING_INSPECTION → NORMAL / QUARANTINED`. 소비기한이 필요한 상품인데 기한이 없으면 NORMAL 거절(TC-05).
- `PUTAWAY`: 수령 위치 `UNSTOWED` → 선반 위치 `STORED`. **이때 E 증가.**
- 위치가 존재·사용 가능·온도구역 일치인지는 mfc-location 의 위치 투영(`MfcLocationStateChanged`)으로 확인한다(2단계).
- **변경점**: 지금은 수령 즉시 판매 가능이다. 이 변경은 mfc-inbound 의 검수·적치 작업과 함께 배포한다(아래 결과).

### 4. 예약 (IV-05) — LOT 단위 조각
- `reservation`(머리) + **`reservation_part`**: part_id, order_line_id, sku, lot_id, kind(`HAND`; `FUTURE` 는 IV-06 때), qty, state, blocked_reason. 상태가 다른 수량은 조각을 나눠 합계를 보존한다(§6).
- 배정: 줄마다 **적격 LOT 중 소비기한 빠른 순(FEFO)** → 동률은 센터 최초 정상 인수 시각 → `lot_id`. LOT 의 A = E_lot − R_lot 만큼 담는다. 줄당 LOT 수 제한 없음(결정 5).
- 적격: 품질 NORMAL 이 판매 가능 수량(E)으로 있고, 소비기한 관리 상품이면 `소비기한 ≥ 배송 약속일 + 최소 잔여일`. 기한 없는 UNTRACKED LOT 은 소비기한 관리 상품에 배정하지 않는다.
- 배송 약속일: 요청의 `promisedDate`(새 선택 필드 — 예약 배송 슬롯 날짜). 없으면 예약 시각의 날짜(즉시 배송).
- 센터 합계 `요청량 ≤ A` 가 맞아도 적격 LOT 이 모자라면 그 줄은 부족(`INSUFFICIENT_STOCK`, 사유 `NO_ELIGIBLE_LOT`). 한 줄이라도 부족하면 전체 거절(지금과 같음).
- 위치는 예약에서 정하지 않는다. 피킹(IV-07) 때 같은 LOT 의 선반 위치에서 세분화한다.
- 확정·해제·만료는 조각 단위 전이. 응답 `Reservation.parts[]` 에 `lotId` 가 채워진다(계약 필드는 이미 있음).

### 5. 소비기한 기준 (Q-05 일부)
- **줄당 LOT 수 제한 없음**(사용자 결정 2026-11-07, 안 A). 적격 LOT 을 FEFO 순으로 필요한 만큼 담는다. 줄당 수량이 1~2개인 주문이 대부분이라 제한의 고객 효과는 작고, 제한하면 기한 임박 소량 재고가 남는다. 섞여도 모두 최소 잔여일 이상이다. 검토한 다른 안: 1개 우선·최대 2개(OMS 문서 — 이 결정으로 대체), 기한 차이 N일 이내만 섞기.
- **최소 잔여일(배송 약속일 기준, 사용자 결정 2026-11-07)**: 냉장 2일, 냉동 30일, 상온 30일. 소비기한 관리 대상이 아닌 상품은 검사하지 않는다.
- 값은 inventory 설정 `pmart.inventory.min-remaining-days.{CHILLED,FROZEN,AMBIENT}`(설정 저장소). 소비기한 관리 상품인데 그 보관 유형 값이 없으면 `CONFIG_REQUIRED`(0 으로 대체하지 않음).
- "소비기한 관리 상품" 여부는 상품 정본(product-service SKU)의 필드여야 한다 — 지금 없다. 추가 전까지는 **수령 LOT 에 소비기한이 있으면 검사, 없으면 UNTRACKED 로 보고 소비기한 관리 상품으로 취급하지 않는다**(시드 데이터는 식품에 모두 기한을 넣는다). product 에 필드가 생기면 그 값으로 바꾼다.

### 6. 원장
- `stock_ledger_entry` 에 단위 키(location_code·lot_id·quality_state·work_state)를 더한다. 한 사건 = 한 `stock_commit`, 기호별 증감과 반영 후 요약 version.
- 새 commit_type: `TASK_EXECUTION`, `QUALITY`, `HANDOVER`, `ADJUSTMENT`, `CORRECTION`(LOT·위치 정정, H 증감 0).

### 7. 기존 데이터 이관
- 마이그레이션은 기존 센터 잔량을 **센터별 `UNTRACKED` LOT 하나 + 이관 위치 `MIGRATION`** 단위 행으로 옮긴다(정상분 NORMAL/STORED, 격리분 QUARANTINED, 미판정 PENDING_INSPECTION). 기존 예약 줄은 그 LOT 의 조각이 된다. H/Q/E/R 합계 불변.
- LAN 시드(MFC-SEED-01, 275 SKU)는 사용자 요청대로 **LOT·소비기한·위치를 채운다**: `CORRECTION` 사건으로 UNTRACKED LOT·이관 위치를 시드 LOT(보관 유형에 맞는 기한)·온도구역 선반으로 나눈다. 총량 불변, `source_system = seed-v2`. LAN 실행 전에 따로 알린다.

## 대안
- **센터·SKU 한 행 유지 + LOT 은 피킹 때만**: 예약이 LOT 을 모르면 격리 → BLOCKED 영향 주문을 특정하지 못하고, 기한 차단을 결제 전에 할 수 없다.
- **단위 행만 두고 요약 없이 매번 합산**: 가용 조회·예약 경합 잠금이 단위 행 전체로 퍼진다. 요약 행 잠금으로 직렬화하는 지금 방식(IV-01 수용 기준 "마지막 1개에 동시 2개 → 성공 ≤1")을 유지한다.
- **LOT 수 제한(1~2개 또는 기한 차이 제한)**: 결정 5.

## 결과
- 단계(브랜치 `mfc-v2` 에서 진행, 3단계 끝에 함께 배포):
  1. inventory 단위 행·LOT·요약·원장, 마이그레이션(이 ADR 1·2·6·7)
  2. mfc-location 위치 마스터·온도구역 + inventory 위치 투영, mfc-quality 판정 최소
  3. inbound 검수·적치 작업 + inventory INSPECT/PUTAWAY(3), 초기 보유 반입이 검수·적치를 거치게
  4. 예약 LOT 배정(4·5), orchestration `promisedDate`, fulfillment 덮음 확인을 조각 단위로
  5. 이후: 6b 피킹·포장(IV-07), 격리 BLOCKED·D·재확보(IV-09), 인계·취소(IV-08), 회수·폐기·실사(IV-10·11)
- 영향: orchestration(예약 요청 필드), fulfillment(예약 조회 `parts[].lotId`), inbound(수령 후 작업), cart·product-content(가용 조회 — 모양 유지), search(취급 여부 — 변화 없음).
- product-service 에 "소비기한 관리 여부" 필드가 필요하다(결정 5) — 3단계에서 계약 변경과 함께.
