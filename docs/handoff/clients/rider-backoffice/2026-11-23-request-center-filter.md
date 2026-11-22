# 요청 — 라이더 백오피스 "센터 고르기"(고른 센터의 라이더·배송만 보기)

- 보낸 영역: platform (PC 1)
- 받는 앱: rider-backoffice
- 작성: pmart-backend, 2026-11-23 — 사용자 "백오피스에서 센터를 고르면 그 해당 센터에 해당하는 라이더 정보를 볼 수 있게 해주면 좋겠는데. 여러 센터 라이더 다 보면 헷갈리지 않을까?"
- 배경: LAN 에 2호점 목포 MFC(`MFC-MOKPO-01`)가 생겨 센터가 둘(1호점 `MFC-SEED-01` 부산진구 MFC). 라이더 앱 배달 지역 목록에도 둘 다 나온다.

## 바라는 화면
1. 머리글(또는 관제·목록 공통 자리)에 **센터 고르기** — "전체 / 부산진구 MFC / 목포 MFC …". 목록은 center-master `GET /centers`(type MFC, state ACTIVE) 이름. 고른 값은 화면을 옮겨도 유지(세션 저장소 정도).
2. 고른 센터가 있으면 그 센터 것만: 아래 목록 호출에 `mfcId` 를 붙인다. "전체"면 지금처럼 붙이지 않는다.
3. 권한은 바뀌지 않는다 — 라이더 운영(`RIDER_OPS`)·관리자는 여전히 모든 센터를 볼 수 있다(보기 거르기일 뿐).

## 지금 `mfcId` 로 거를 수 있는 것(바로 쓸 수 있음)
- execution: `GET /deliveries`·`/deliveries/counts`·`/delivery-help-requests`·`/delivery-stats`·`/return-handovers`
- dispatch: `GET /dispatch-delays`·`/metrics/on-time-deliveries`
- tracking: `GET /rider-locations`(관제)

## 아직 못 거르는 것(백엔드 요청 RS-7 — PC 5)
- rider `GET /riders` — 지금은 `operatingMfcId`(지금 운행 중인 센터)만. 배달 지역 센터로 거르는 `mfcId` 를 요청했다. 그 전까지 라이더 찾기에 센터를 고르면 `operatingMfcId` 로 대신하되 "운행 중인 라이더만" 이라고 표시하거나, 센터 거르기를 끄고 안내.
- rider `GET /riders/withdrawals`, execution `surcharge-requests`·`rider-payouts`·`rider-earnings/adjustment-queue`, dispatch `rider-assignments` — PC 5 판단 중.
- 생기면 회신으로 알린다.
