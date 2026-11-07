# ADR-0046 집품 뒤 취소(IV-08) — 현장 중지가 작업을 끝내고 집품·포장분을 취소 복귀 대기로, 복귀 작업이 선반으로. 결품 전체 취소는 자동

- 상태: 승인 (2026-11-08, 사용자 "그래 이제 마저해" — IV-08 진행)
- 관련: ADR-0040(재고 H/Q/E/R/A/D), ADR-0043(6b — 피킹 뒤 중지 PENDING), ADR-0044(결품 보류·재확보), ADR-0045(결품 사가 — 전체 취소는 상신), 흐름 문서 cancel-vs-handover §2 a·§6, order-checkout-saga §2.1, MFC IV-08

## 배경
6b 뒤 피킹이 시작된 주문의 작업 중지는 `PENDING`(작업 유지)이었고, inventory 는 집품이 시작된 예약의 단순 해제를 막았다(`RESERVATION_PICKED`). 그래서 S3(취소 사가)는 끝없이 기다리고, 결품 사가의 `CANCEL_ALL`·주문 전체 결품은 상신(수동)으로 남았다. 흐름 문서(cancel-vs-handover §2)는 "피킹 후: E−q, R−q → CANCEL_RETURN_PENDING → 복귀·적격 확인 후 E/A+q(검증 없이 판매 가능 복구 금지)" 로 정해 두었다.

## 결정
1. **현장 중지(mfc-fulfillment, 전체 중지만)**: 피킹이 시작된 주문이면 중지 기록을 PENDING 으로 남긴 뒤 같은 트랜잭션에서
   - 남은 PICK·PACK 행 취소, 끝나지 않은 작업은 완료량이 있으면 `CLOSED_PARTIAL`, 없으면 `CANCELLED`(작업자 실적은 이후 409).
   - 밀봉 포장 `INVALIDATED`, 출고 `CANCELLED`.
   - 복귀 대상 = 반영된 PICK 실적(도착 위치) − 밀봉 포장(같은 위치) → PICKED, 밀봉 포장 → PACKED(주문 줄·SKU·LOT·위치별).
   - 복귀 대상이 있으면 출고마다 **RETURN 작업**(`<출고>-RETURN`, WAITING).
   - 진행 중(재고 결과 대기) 실적·포장이 있으면 준비를 미룬다(그 결과가 나야 집품량이 확정) — sweep 이 다시.
2. 커밋 뒤 **inventory `POST /internal/reservations/{id}/cancel-returns`**(키 = 중지 요청 ID): 단위 PICKED/PACKED → `CANCEL_RETURN_PENDING`(E−q, H 그대로), 모든 HELD·CONFIRMED 조각 R−q, BLOCKED 까지 조각 RELEASED, 예약 RELEASED. returns 가 조각별 집품·포장량과 다르면 409 `RETURN_MISMATCH`(실물 누락 방지). 반영되면 중지 RESOLVED — `packages[]` 모두 STOPPED(포장별 + 출고의 포장 전 단위 NOT_PICKED·PICKED), RETURN 작업 READY, `WorkStopResult`. 결과 모름·미전달은 sweep(`pmart.fulfillment.stop-sweep-interval`, 기본 30초), 재고 거절은 `last_error` 로 남기고 PENDING(운영자).
3. **복귀 작업**: 점원이 RETURN 을 시작해 실적 `executionType RETURN`(포장 위치 → 선반, 한 실행 = 한 출발 위치) → inventory **MOVE**(지금은 취소 복귀만: NORMAL·CANCEL_RETURN_PENDING → 선반 NORMAL·STORED, E+q; `resultQuality QUARANTINED` 면 격리 Q+q). 목적지는 등록·ACTIVE·보관/피킹 구역·온도대 = SKU 보관 유형. 다 옮기면 COMPLETED. 판매 가능 복구는 이 실행(사람의 확인)으로만.
4. S3 는 그대로: STOPPED 판정 뒤 RELEASE_RESOURCES(예약은 이미 RELEASED → 변화 없는 성공) → APPLY_ORDER_CANCEL → OrderCancelled → claim 전액 환불.
5. **claim**: 시스템 전체 취소(`/internal/claims/system-cancellations`)는 주문 `PREPARING` 도 받는다(고객 접수는 CONFIRMED 만 — 그대로). 사유 `OUT_OF_STOCK` 추가(CancellationRequested 사유 그대로 전달). 진행 중 결품 부분 환불 클레임(현장 중지 없음)은 "진행 중인 취소" 가 아니다(V3 — 유일 인덱스를 중지 있는 클레임으로 좁힘, 결품 부분 환불이 같은 주문에 둘 진행되던 충돌도 해소). 전체 환불액 = 원 승인액 − 이 주문에 이미 나간(실패 아닌) 환불 합(payment FULL = 남은 잔액).
6. **결품 사가**: `CANCEL_ALL` 은 바로, `CANCEL_OOS_ONLY` 라도 결품이 남은 수량 전부면 CHECK_ORDER 뒤 **REQUEST_FULL_CANCEL**(claim 시스템 전체 취소, reason OUT_OF_STOCK, 키 `sagaId:REQUEST_FULL_CANCEL`) → 클레임 ID 로 COMPLETED. 같은 주문의 다른 결품 사가가 먼저 요청했으면 claim 이 진행 중 클레임을 돌려준다. 선택 없음은 그대로 상신(CS 미구현).

## 대안
- 중지 때 집품분을 바로 판매 가능(E+q)으로: 실물이 포장대에 있어 선반 잔량과 어긋나고, 문서가 "검증 없이 판매 가능 복구 금지" 로 막았다.
- 복귀를 inventory 혼자(작업 없이): 누가 언제 옮겼는지 기록이 없다. 작업(RETURN)으로 점원 단말에 띄운다.
- 결품 전체 취소의 환불을 결품 사가가 직접: 현장 중지·자원 해제·주문 취소가 S3 와 겹친다. claim 시스템 전체 취소로 S3 에 맡긴다.

## 결과
- 계약: inventory 0.7.0(`cancel-returns`, MOVE 취소 복귀), fulfillment 0.3.0(TaskType RETURN, executionType RETURN·lines[].resultQuality, 중지 판정 packages), claim(system-cancellations reason OUT_OF_STOCK·PREPARING).
- 표: fulfillment V5(`task.returns`, task_type RETURN, `order_work_stop.cancel_return·package_verdicts·last_error·resolved_at`), claim V3(활성 취소 유일 인덱스).
- 남은 것: 피킹 뒤 **부분** 중지(지금 PENDING 유지 — 고객 부분 취소는 접수하지 않음), 일반 선반 이동 MOVE, 인계 fence(6c), 재고 거절된 중지의 운영자 처리(BO-12), 복귀 중 분실(IV-11).
