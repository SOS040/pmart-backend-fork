# ADR-0078 피킹이 끝나면 그 출고 PACK 을 피커에게 바로 배정

- 상태: **승인** (2026-11-20)
- 사용자 결정 2026-11-20 (시뮬레이터 세션 경유, "응"): 피킹을 끝낸 작업자가 PDA 에서 "다음 작업 받기"·"패킹대로"를 누르지 않아도 그 출고의 포장 작업이 바로 그 사람에게 간다.
- 계기: 주문 83072555 — PICK 이 14:27 에 끝났는데 PACK 이 담당 없이 14분 READY 로 있었다(capacity 다음 작업 받기는 피커가 당길 때만 준다).
- 관련: 2026-11-18 사용자 결정 "피커만 받음"(capacity 0.19.0 `pickerMayTake`·`OWN_PACK_RANK`), ADR-0043(배정 정본 = capacity), ADR-0077

## 결정
1. 배정 정본인 **mfc-capacity** 가 한다. mfc-fulfillment 가 PACK 을 대기에서 풀 때 내는 `TaskProgressed`(type PACK, previousState WAITING, state READY)에 `pickedBy`(같은 출고 PICK 을 끝낸 작업자)를 싣고, capacity 가 이 사건을 구독해 다음 작업 받기와 **같은 SYSTEM 배정**(reason `PICKER_PACK`, 같은 `AssignmentPublished`)을 만든다 → fulfillment 투영이 READY → ASSIGNED.
2. 하지 않는 경우(예전처럼 READY — 다음 작업 받기가 피커에게만 준다): 그 작업에 이미 활성 배정이 있음(덮지 않음), 피커가 등록·ACTIVE·(일일크루 그날 등록)·유효 업무에 PACK 이 아님, 휴게 중, 근무 밖(근무를 판단하지 못한 경우 포함).
3. 멱등: 같은 사건은 inbox, 다른 배정과의 경합은 작업당 활성 1건 유일 색인(실패면 사건을 다시 처리 → 이미 배정 → 그대로).
4. PDA 의 다음 작업 받기는 들고 있는 열린 작업의 배정을 먼저 돌려주므로 이 배정을 그대로 받는다(바뀐 것 없음).

## 적용
- mfc-fulfillment 0.31.0: `FulfillmentEvents.taskProgressed` 가 PACK 이면 `pickedBy`, 결품 해결로 PICK 이 끝날 때도 PACK 준비 사건을 낸다.
- mfc-capacity 0.32.0: `AssignmentService.packReleasedToPicker`·`assignPackToPicker`, 구독 `infrastructure/FulfillmentEventConsumers`(바인딩 `fulfillmentEvents-in-0` = `mfc.fulfillment.v1`). **설정 저장소(pmart-config)의 mfc-capacity-service.yml 에 함수 `fulfillmentEvents`·바인딩을 더해야 켜진다**(시험 사본은 고침). 설정 전에는 예전 동작.
- 사건 스키마 TaskProgressed `pickedBy`(선택).

## 결과
- 피킹이 끝나면 포장이 곧바로 피커의 일이 된다. 맡을 수 없는 피커면 센터 관리자 배정 또는 (피커가 돌아와) 다음 작업 받기로.
