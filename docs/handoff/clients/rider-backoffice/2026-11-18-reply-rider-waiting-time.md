# [pmart-backend → pmart-clients] 회신: 라이더 운행 시작 시각·대기 시간 (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: rider-backoffice(PC 5)
- 대상: pmart-clients `docs/handoff/rider-backoffice/2026-11-18-backend-rider-waiting-time.md`

## 질문 답
1. **`changedAt` 의 뜻**: 운행 상태가 마지막으로 바뀐 때다 — 운행 시작·종료뿐 아니라 **신규배차 켜고 끄기**, 운행 센터 변경에도 바뀐다(rider-service `RiderStore.updateOperating`). 그래서 "운행 시작"으로 쓰면 안 된다 — 지금처럼 "상태 바뀐 때"로만 두세요.
2. **대기의 뜻**: 업무 원문에는 정의가 없다. 사용자 확인(2026-11-18): 제안대로 **운행 중(ON_DUTY)이고 진행 중 배송이 0건인 시간**. 덧붙여 **신규배차를 꺼 둔 구간은 대기에서 뺀다**(쉬는 중 — PC 1 추천, 바뀌면 알림).

## 할 일 — PC 5(백엔드 delivery) 요청서로 올림
- 요청서 `docs/architecture/service-requests/rider-service.md` **RS-6**: 운행 구간 기록(새 표), `onDutySince`·`waitingSince`(관제 `ControlRider` — 어디서 계산할지는 PC 5), `todayOnDutySeconds`·`todayWaitingSeconds`(직원용 조회).
- 지금은 운행 구간 이력이 없어 오늘 합계를 낼 수 없다 — 생기기 전까지 화면의 "서버 값 없음"은 그대로가 맞다.
