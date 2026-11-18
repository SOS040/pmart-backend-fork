# [pmart-backend → pmart-mfc-simulation] 요청: 작업자마다 다른 속도 — 서버 `speedFactor` 만큼 시뮬레이터 작업자도 (2026-11-18)

- 보낸 영역: platform(PC 1)
- 근거: 사용자 2026-11-18 "서버의 speed_factor 를 작업자별로 다 다르게 넣은 다음, 시뮬레이터 쪽에 그 값만큼 걷는 속도를 다르게 해 달라고 요청"
- 서버 값 정본: mfc-capacity-service `mfc_worker.speed_factor`(0.11.0, 사용자 결정 2026-11-09 — 배정 추천의 작업 시간 = 작업 추정 분 ÷ 배율, 0.5~2.0, 1.0 보통)

## 서버에 넣은 값 (LAN, 2026-11-18)
- MFC-SEED-01 재직 작업자 **71명 모두 다른 값**: 0.70 ~ 1.40, 0.01 간격(고정 씨앗으로 섞어 한 명씩). 센터 관리자 API `POST /mfcs/{mfcId}/workers/{workerId}/speed-changes`(사유 "LAN 시험 — 작업자마다 다른 속도")로 넣어 변경 기록이 남아 있다.
- 가상 작업자 `mfcw-pp-01`~`10` 도 이 71명 안에 있다 — 각자 다른 값.

## 읽는 법
- `GET /api/v1/mfcs/{mfcId}/workers/{workerId}`(작업자 본인 토큰 가능 — 가상 작업자는 자기 토큰의 subject 로) 또는 목록 `GET /api/v1/mfcs/{mfcId}/workers?state=ACTIVE`(그 센터 운영자) 의 **`speedFactor`**.
- 시작할 때 한 번 읽고, 바뀔 수 있으니(센터 관리자가 고침) 주기적으로(예: 작업 받을 때) 다시 읽으면 좋다. 못 읽으면 1.0.

## 부탁
1. 시뮬레이터 작업자의 **걷는 속도 = `walkSpeed`(1.1 m/s) × `speedFactor`**.
2. (권장) 서버 배정 추천은 이동뿐 아니라 **작업 시간 전체를 ÷ 배율**로 본다. 그래서 스캔·담기·포장 시간(`pickScanSecPerLine`·`pickSecPerUnit`·`packBaseSec`·`packPerUnitSec`)도 **÷ `speedFactor`** 로 하면 서버 추천과 시뮬레이터 움직임이 맞는다. 걷는 속도만 바꿀지는 시뮬레이터가 정해도 된다 — 정하면 회신에 적어 주세요.
3. 화면에 작업자별 배율(예: "×1.23")을 작게 보이면 배정 추천이 빠른 작업자에게 더 주는지 눈으로 보기 쉽다(선택).

## 회신
`pmart-mfc-simulation/docs/backend-requests.md` 또는 이 폴더 회신 파일로.
