# ADR-0091 자동 발주 초안과 센터 발주 → DC 이관(TRF)

- 상태: **승인** (2026-11-27)
- 근거: 사용자 2026-11-26 "자동보충 만들어" → "추천 자동 발주 + 승인". DC 시뮬레이터 R-27(2026-11-26, 사용자 "mfc 백오피스에서 입고신청하면 이 시뮬레이션이랑 dc 백오피스에 연동돼서 자동으로 새 trf 들어왔다고", "임시다리 말고 백엔드 R-27 만들라고"). 온도대 나누기·모르는 SKU 422·기한 READY 는 사용자가 PC 3 에 정해 준 것(2026-11-27 MFC 백엔드 세션 전달).
- 임시: replenishment-service(SCM)가 생기기 전까지. SCM 이 생기면 승인·TRF 발행은 SCM 의 `TransferApproved` 로 옮기고 이 길은 없앤다.
- 바꾸는 것: `docs/business/flows/forecast-based-supply-request.md` "정책 자동 요청 없음" — 자동으로 만드는 것은 **초안(SUBMITTED)** 뿐이고, 승인은 그대로 사람이 한다.

## 결정
1. **자동 발주 초안**(mfc-inbound `AutoSupply`): 매일 06:00 Asia/Seoul(`pmart.inbound.auto-supply-enabled`, 기본 꺼짐) 인계 실적이 있는 센터마다 최근 28일에 판 SKU 의 발주 추천(사람이 보는 `GET /supply-recommendations` 와 같은 계산) 중 추천 수량이 있는 줄로 공급 요청 SUBMITTED. 제출자 `SYSTEM`, ref `AUTO-<센터>-<yyyyMMdd>`(하루 한 번), 미해결 요청에 이미 있는 SKU 는 뺀다. 수동 실행 `POST /internal/auto-supply-runs`.
2. **승인은 그대로** 그 센터 MFC_SUPERVISOR(MI-4).
3. **보급 DC**(`pmart.inbound.supplying-cdc`, 설정값 하나 — LAN `CDC01`)가 정해져 있으면 승인 때:
   - MFC 쪽 초기 입고(`initial_stock_load` RCV-SR)는 **만들지 않는다**(DC 다리와 이중으로 잡히던 문제 — MFC 시뮬레이터 R-51).
   - 승인 줄을 **온도대별로 나눠 TRF 하나씩**(cdc-outbound 인수 조건 TEMPERATURE_ZONE 은 하나뿐). 온도대 = mfc-inventory SKU 사본 `storageType`(AMBIENT·CHILLED·FROZEN). 모르는 SKU 가 있으면 승인하지 않는다(422 `STORAGE_TYPE_UNKNOWN`), SKU 사본 조회가 안 되면 503(모두 상온으로 보내지 않는다).
   - trfId: 온도대가 하나면 `TRF-<공급 요청 ID>`(DC 시뮬레이터 임시 다리와 같은 번호), 여럿이면 `TRF-<공급 요청 ID>-<AMBIENT|CHILLED|FROZEN>`. `linkedTrfId` = 첫 TRF, 전체는 `transfers`.
   - 각 TRF 의 입고 예정(`inbound_promise` EXPECTED, fromCdc)을 같은 트랜잭션에서 만든다. 출발 사건(`TransferDeparted`·매니페스트)이 같은 trfId 에 붙는다.
   - cdc-outbound `POST /internal/transfer-intakes` 본문: trfVersion 1, cdcId, mfcId, approvalRef = 공급 요청 ID, **deadlineMeaning READY, dueAt = 희망 사용준비 시각**(이미 지났으면 승인 + `supply-due-after` 1일), conditions `[{kind: TEMPERATURE_ZONE, value}]`, lines approvedQty. Idempotency-Key `mfc-inbound-supply-trf:<trfId>`. TMS 는 dueAt − 12시간의 날을 출고일로 잡는다.
   - 호출은 트랜잭션 밖 — 승인 응답 직후 한 번, 실패는 `supply_request_transfer` 에 남기고 15초 주기로 1분부터 두 배·최대 30분 다시. 401·403·404·408·429·5xx·연결 실패는 다시, 그 밖 4xx(400·409·422)는 내용 거절이라 멈추고 사유를 남긴다.
4. 보급 DC 가 비어 있으면 예전 동작(승인 = RCV-SR 초기 입고 예정, MI-4).

## 결과
- 센터 관리자가 승인하면 DC 백오피스·DC 시뮬레이터에 새 TRF 가 들어온다(RECEIVED → 할당). DC 쪽 재고가 없으면 ALLOCATION_FAILED(cdc-outbound 가 판정).
- 보급 DC 를 켜면 승인 → 바로 입고 예정(RCV-SR)이던 흐름이 "DC 출발 → 도착 → 수령"으로 바뀐다(MFC 시뮬레이터 R-48 이 쓰던 길).
- 남은 것: SCM(replenishment) 이 생기면 대체, 센터별 보급 DC(지금은 하나).
