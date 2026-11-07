# ADR-0044 피킹 결품·파손(IV-09 1부) — 파손은 격리, 못 찾은 물량은 분실 조사 보류, 예약 BLOCKED, 같은 SKU 적격 LOT 자동 재확보

- 상태: 승인 (2026-11-07, 사용자 "다음" — MFC 할 일 순서의 IV-09. 고객 결과 처리는 2부로 분리)
- 관련: ADR-0042 결정 1(다른 LOT 대체 — 같은 SKU·적격 기한), ADR-0043(6b), MFC 문서 IV-09·§2 수량 정의("분실조사"는 별도 제외 축), 흐름 문서 order-checkout-saga §2.1

## 배경
6b 는 결품 보고를 받으면 작업만 BLOCKED 로 두고 재고는 그대로였다. IV-09 는 "실제 존재하는 이상품만 Q 전환, 분실 의심량을 격리 현물로 생성하지 않음, 적격 상실분 E 제외와 연결 R 의 BLOCKED 전환을 함께, 적격 대체 확보 시 D 감소/R 증가" 를 요구한다. §2 는 E 제외 사유로 "분실조사" 를 품질·업무 상태와 다른 축으로 둔다.

## 결정
1. **보고 단위**: fulfillment 는 결품 보고를 집품과 섞지 않은 **한 줄짜리 실적**으로 받는다(주문 줄·예약 LOT·발견 선반·수량·사유). 섞으면 집품만 반영되고 보고는 실패하는 등 일부 반영이 생긴다.
2. **inventory `POST /internal/shortage-reports`** (한 트랜잭션, reportId 로 한 번만):
   - **DAMAGED**: 그 선반·LOT 의 NORMAL·STORED → QUARANTINED(Q+q, E−q). 실제 있는 이상품.
   - **SHORT·BLOCKED**: 실물을 못 찾음 → **분실 조사 보류** `stock_hold`(E−q, H·단위 행 그대로). 보류된 물량은 집품·이동할 수 없다(원장이 "단위 − 보류" 안에서만 감소 허용). 피킹 지시 위치 잔량에서도 뺀다. 실제 손실 확정·보류 해제는 실사(IV-11).
   - 예약: 그 주문 줄·LOT 의 확정 조각 중 **아직 집지 않은 양**에서 R−q.
   - **재확보**: 같은 SKU 의 적격 LOT(처음 배정과 같은 소비기한 기준·배송 약속일·FEFO)에서 신규 가용(A=E−R) 안으로 다시 잡는다 — 새 확정 조각(R+r). 다른 주문 예약은 건드리지 않는다(Q-13 다주문 배분은 미결이라 하지 않음). 다른 SKU 로는 바꾸지 않는다.
   - 못 잡은 양 u = q − r 는 **BLOCKED 조각(이행부족 D)**, `ReservationBlocked` 발행.
3. **fulfillment**: u = 0 이면 작업은 그대로 진행(작업자는 새 LOT 을 피킹 지시에서 본다). u > 0 이면 작업 BLOCKED + `LineShortageReported(shortQty = u)`.
4. **고객 결과(2부, 다음)**: `LineShortageReported` 이후 고객 사전 품절 선택(CANCEL_OOS_ONLY → 부분 취소·부분 환불, CANCEL_ALL → 전체 취소, 선택 없음 → CS 이관)은 흐름 문서 §2.1 대로 orchestration·claim·payment 에서 — 이번 범위 밖. 그때까지 BLOCKED 작업은 감독자 화면에 남는다.

## 대안
- 못 찾은 물량을 QUARANTINED 로: 격리 현물(Q)이 있는 것처럼 보이고 문서가 금지한다. 택하지 않음.
- 못 찾은 물량을 바로 H 차감(손실 확정): 실사 승인 없이 손실을 확정한다(IV-11 위반). 택하지 않음.
- 품질 상태에 "분실 의심" 값을 추가: 문서의 품질 상태 목록을 바꾼다. 별도 보류 표로.

## 결과
- inventory 계약 0.6.0, V8(`stock_hold`, `shortage_report`), `Ledger.postWithHolds`, `ShortageService`, `ReservationService.resecure`.
- fulfillment: 결품 실적 검증(한 줄·LOT·선반), `InventoryClient.reportShortage`.
- 남은 것: 2부(고객 결과), 보류 해제·손실 확정(IV-11), 격리품 이동·폐기(IV-10), 6a 재시도(D6-5)의 재확보 확장.
