# mfc-inventory-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-inventory-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-inventory-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.inventory.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.inventory.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-inventory-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-inventory-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8301.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- 상품 등록 슬라이스(2026-10-30): `commerce.product.v1` 의 SkuIssued·ProductVersionPublished → `sku_projection`(오래된 product_version 무시). 센터 취급 `center_sku_handling` — **레코드 없음 = OFF**, 이벤트로 만들지 않고 자기 센터 운영자(`centers` 클레임)가 `/mfcs/{mfcId}/sku-handlings`(단건·일괄 500행 전부 또는 전무)로 켠다. 켜기는 ACTIVE SKU 만. 이력 `center_sku_handling_history`. 바뀔 때만 같은 트랜잭션에서 `SkuHandlingChanged`(집합 SkuHandling → 토픽 `mfc.sku-handling.v1`, 무제한 보관, 2026-11-05) — 구독은 검색 색인. `POST /internal/sku-handling-republications[?mfcId]` 는 상태를 바꾸지 않고 지금 상태를 같은 버전으로 다시 알린다(사건 도입 전 취급의 최초 연결·구독 쪽 복구, 최대 20000건).
- 재고 원장·예약(슬라이스 4 S1, 2026-10-30): 센터·SKU 한 행 `stock_balance`(H/Q/E/R, CHECK 0≤R≤E≤H), 확정 단위 `stock_commit`·`stock_ledger_entry`, 수령 중복 방지 `stock_receipt`(receiptId+내용 지문). ConfirmReceipt 는 검수 단계가 없어 정상=H+E. 예약은 SKU 합산·취급 OFF 거절·잔량 행 SKU 오름차순 `FOR UPDATE` 후 all-or-nothing, 거절(422)은 멱등 기록 없음. 확정·해제(전량만, 이미 끝난 예약 해제는 변화 없는 성공), 만료 작업 `HoldExpiry`(`pmart.inventory.hold-expiry-interval`, SKIP LOCKED) → EXPIRED·R−q·InventoryHoldExpired(`mfc.inventory.v1`). 잠금 순서 예약 행 → 잔량 행. `/internal/availability`·`commits`·`requests` 구현. 작업 실행·인계·품질·조정·스냅샷은 501.
- **재고 모델 v2(ADR-0040, 2026-11-07, 브랜치 `mfc-v2` — 아직 배포 안 함)**: V4 단위 행 `stock_unit`(위치·SKU·LOT·품질·업무상태) + `stock_lot`·`stock_lot_balance` + 센터 요약 `stock_balance`(기존 응답 유지). 모든 수량 변경은 `Ledger`(센터 요약 → LOT 요약 → 단위 행 잠금, 감소분 잔량·불변식 선검사, 원장에 단위 키). 수령은 수령 위치 UNSTOWED·PENDING_INSPECTION(H 만), `CommitTaskExecution` INSPECT·PUTAWAY(적치 때 E+), `GET /internal/receipts/{id}` 로 lotId 조회, `stock_execution` 으로 executionId 한 번. 예약은 LOT 조각 `reservation_part`(FEFO, 줄당 LOT 수 제한 없음, 최소 잔여일 `pmart.inventory.min-remaining-days` 냉장2·냉동30·상온30 — 배송 약속일 `promisedDate` 기준, 미설정 보관 유형은 CONFIG_REQUIRED, 적격 부족은 NO_ELIGIBLE_LOT). 기존 데이터는 UNTRACKED LOT·위치 `MIGRATION` 으로 이관(StockMigrationV4IT). PICK·PACK·MOVE·인계·품질·조정은 아직 501. 위치 투영 `location_projection`(V5, `MfcLocationStateChanged` 구독 바인딩 `locationEvents-in-0`)으로 수령 위치(RECEIVING·RETURN)·적치 목적지(STORAGE·PICKING, 온도대 = SKU 보관 유형, 모르면 CONFIG_REQUIRED)·출발 위치 실사 잠금을 검사(`Locations`).
- **상품별 소비기한 보장(ADR-0041, 2026-11-07)**: V6. SKU 투영에 `ProductVersionPublished.skus[].shelfLifeGuarantee`(최소 잔여일·제조 후 최대 경과일). 배정(`LotAllocator.ShelfRule`): 최소 잔여일은 상품 값 우선·없으면 보관 유형 설정, 상품이 잔여일을 보장하면 기한 없는 LOT 제외, 제조일 보장이면 제조일 있는 LOT 만(`약속일 − 제조일 ≤ N`). LOT 제조일 `stock_lot.manufacture_date`(INSPECT `manufactureDate`, CORRECTION `toManufactureDate`) — LOT 정체성(SKU·표기·기한·제조일). 센터 재고 상세 `lots[].manufactureDate`.
- 예약 유효시간: 요청 `ttlMinutes` 가 없으면 `pmart.inventory.default-hold-ttl`(ADR-0042, 10분), 그것도 없으면 CONFIG_REQUIRED. 계약의 `default: 10` 은 지웠다(생성 DTO 가 늘 채워 설정을 우회).
- **피킹·포장(슬라이스 6b, 2026-11-07, ADR-0043, V7)**: `CommitTaskExecution` PICK(선반 NORMAL·STORED → 포장 구역 PICKED, `Locations.requirePacking`)·PACK(같은 위치 PICKED → PACKED). `reservationId`·`lines[].orderLineId` 필수, 예약 CONFIRMED, 줄은 같은 주문 줄·LOT 의 CONFIRMED 조각 남은 양 안에서(`PickRules`: LOT_MISMATCH·PICK_QTY_EXCEEDED·LINE_NOT_IN_RESERVATION) → `reservation_part.picked_qty/packed_qty`. 잠금 순서 예약 행 → 조각 → 잔량. 총량(H/E/R/A) 불변. `GET /internal/reservations/{id}/pick-plan`(조각·LOT 기한·LOT 선반 위치별 잔량). 집품 시작된 예약의 해제는 409 `RESERVATION_PICKED`(IV-08 전). MOVE 는 501.
- **결품·파손 보고(IV-09 1부, ADR-0044, V8, 계약 0.6.0)**: `POST /internal/shortage-reports`(`ShortageService`, reportId 한 번 — `shortage_report`). DAMAGED = 선반 NORMAL·STORED → QUARANTINED, SHORT·BLOCKED = 분실 조사 보류 `stock_hold`(E 만 −q, 단위 행 그대로). `Ledger.postWithHolds`: 보류는 E 에서만 빼고, 단위 감소는 "단위 − OPEN 보류" 안에서만(보류분 집품·이동 불가). 예약: 줄·LOT 확정 조각의 미집품분에서 R−q(조각 줄이기 / 다 빠지면 RELEASED `SHORTAGE:<id>`) → `ReservationService.resecure`(같은 SKU 적격 LOT, 신규 가용 안, 확정 조각 R+r) → 남은 양 BLOCKED 조각 + `ReservationBlocked`. 피킹 지시 위치 잔량은 보류를 뺀다.
- 이행부족 해제(ADR-0045): `POST /internal/reservations/{id}/shortfall-releases`(결품 사가) — 그 줄 BLOCKED 조각을 qty 만큼 RELEASED(사유 OUT_OF_STOCK_CANCELLED), 현물·R 변화 없음, 초과 409.
