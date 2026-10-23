# Delivery 상태표 (delivery-dispatch, delivery-execution, rider, delivery-capacity)

근거: Delivery 문서 2.1·3.2~3.6·4.2·5.1·5.4·6.4·7.2·9.6, OMS 9·A.2.6, ADR-0006/0008

## Delivery (dispatch 생성, execution 수행)

| 상태 | 소유 | 의미 |
|---|---|---|
| CREATED | dispatch | 배송 단위 생성(ShipmentReady 또는 회수/재배송 요청) |
| DISPATCHING | dispatch | 배차 시도 진행 |
| ASSIGNED | dispatch | 유효 수락 1건 확정(assignmentVersion) |
| DISPATCH_FAILED | dispatch | 기사 없음/반복 거절/만료/장애 → 점포·CS 안내 |
| CANCELLED_BEFORE_PICKUP | dispatch | 픽업 전 취소(상품은 점포 보관) |
| STORE_ARRIVED | execution | 가게 도착(인계 아님) |
| PICKED_UP | execution | 모든 포장 PackageHandedOver 확인 |
| CUSTOMER_ARRIVED | execution | 고객 도착 |
| DELIVERED | execution | 사진 증빙 확인 후 서버 완료. 시간 경과 자동완료 없음 |
| FAILED | execution | 전달 실패(사유·보관 주체) |
| SUSPENDED | execution | 중단(사고·도움요청) → 지원센터 처리 |
| RETURNING → RETURNED_TO_STORE | execution | 픽업 후 취소·회수 |

완료 후 사고 신고는 DELIVERED 유지 + Incident.

## DispatchAttempt / Offer (dispatch)

Attempt: `OPEN → ASSIGNED | EXHAUSTED(반경 단계 소진) | CANCELLED`. 반경 1km→2km→3km `[외부 참고값]`, 재시도 최대 2회·10분 `[외부 참고값]`.
Offer: `SENT → ACCEPTED | REJECTED | EXPIRED(1분) | SEND_FAILED | LATE(EXPIRED / ALREADY_ASSIGNED / ORDER_CANCELLED / INELIGIBLE)`. 동시 수락은 1명만 ACCEPTED.

## RiderOperatingState (rider)

`OFF_DUTY → ON_DUTY_ACCEPTING → ON_DUTY_PAUSED → OFF_DUTY`. 앱 종료·네트워크 단절로 수행 중 배송 자동 취소 금지(마지막 통신 시각 기록). 배차 모드 `MANUAL / AI_RECOMMENDED`, 하나씩 배달하기 ON/OFF.

## RiderRestriction (rider)

`NORMAL → NEW_DISPATCH_BLOCKED → SUSPENDED → TERMINATED` (증빙·소명 기반, 후기만으로 자동 제재 없음). 제한 시 dispatch 제안 제외 + mfc-fulfillment 인계 차단.

## SlotHold (delivery-capacity)

`HELD → CONFIRMED | RELEASED | EXPIRED` ; `UNKNOWN`. 정원 NOT_SET → CONFIG_REQUIRED(0/무제한 아님).

## Settlement (execution; 지급 실행은 외부 회계)

`ESTIMATED → PENDING_CONFIRMATION(완료 후 5시간 `[외부 참고값]`) → CONFIRMED → PAYING → PAID | PAY_FAILED | ADJUSTED`. 고객 환불은 수행료를 자동 취소하지 않는다.

## ReturnPickup 수행 축

`ASSIGNED → PICKED_UP(고객 수거) → DELIVERED_TO_STORE(점포 반납) → STORE_CONFIRMED(점원 수령 확인)`.
