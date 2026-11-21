보낸 영역: platform

# 회신 — 작업자 현재 위치(도면 좌표) API

받는 앱: mfc-backoffice (PC 3)
요청: `pmart-clients/docs/handoff/mfc-backoffice/2026-11-21-backend-worker-floor-positions.md`
날짜: 2026-11-21
상태: 백엔드 구현·시험 완료, **LAN 배포 전**(배포는 PC 1 이 따로)

## 고른 안: 1번 — `GET /api/v1/mfcs/{mfcId}/worker-positions`
작업자 정본인 mfc-capacity-service 가 이미 여러 서비스의 작업 내역을 모으고(F-7 업무 내역) mfc-location-service 거리표도 부르고 있어서,
같은 자리에서 "마지막 스캔 칸 → 도면 좌표"까지 바꿔 주는 것이 가장 자연스러웠다. 화면이 칸 좌표를 다시 찾을 필요가 없다(2번 안보다 호출이 적음).

계약: `contracts/openapi/mfc-capacity-service.yaml` 0.34.0 (원천: mfc-fulfillment-service 0.33.0·mfc-inbound-service 0.20.0 `/internal/worker-last-locations`, mfc-location-service 0.10.0 거리표 `attributes[].position`).

## 요청·응답
- query `sinceMinutes`(선택, 기본 480 = 8시간, 1~1440) — 이 시간 안에 기록이 없는 작업자는 나오지 않는다.
```json
{
  "mfcId": "MFC-…",
  "asOf": "2026-11-21T05:00:00Z",
  "since": "2026-11-20T21:00:00Z",
  "items": [
    { "workerId": "…", "xM": 4.5, "zM": 12.0, "source": "SCAN",
      "locationCode": "A-C1-01-1-01", "activityType": "PICK", "at": "2026-11-21T04:58:10Z" }
  ],
  "unplaced": [
    { "workerId": "…", "locationCode": "PAK-A-01", "activityType": "PACK", "at": "…", "reason": "NO_POSITION" }
  ],
  "unavailableSources": []
}
```
- `xM`·`zM`: mfc-location `FloorPosition` 과 같은 좌표계(x 동쪽·z 남쪽 m, 원점 북서 모서리) — 칸의 "서는 지점".
- `source`: 지금은 늘 `SCAN`(마지막으로 처리·스캔한 칸). `ESTIMATE` 는 나중 추정용 자리 — 값 목록에만 있다.
- `at`: 그 칸을 스캔한 시각(기준 시각). 화면에 "마지막으로 본 자리 · 시각"으로 보여 주면 된다.
- `items` 는 `at` 늦은 순.

## 뜻(semantics)
- **실내 측위가 아니다.** 작업자마다 마지막 기록 한 건의 칸이다.
  - mfc-fulfillment-service: PICK(집은 선반)·RETURN(놓은 칸)·PACK(패킹대)
  - mfc-inbound-service: 입고 수령(수령 칸)·품질검사(작업 칸)·적치/격리 이동(놓은 칸)·이동·보충(내려놓았으면 도착 칸, 집기만 했으면 출발 칸)·회수품 반입
  - 재고 실사·품질 판정은 칸 스캔 기록이 없어 아직 빠진다.
- 두 서비스 중 더 늦은 기록이 그 사람의 자리. 기록 사이 이동(걷는 중)은 알 수 없다.

## 실패·빈 값
- 원천 하나가 안 되면 그 이름이 `unavailableSources` 에 들어가고 나머지는 보인다.
- mfc-location-service 조회가 안 되면 모두 `unplaced`(reason `LOCATION_UNAVAILABLE`) + `unavailableSources` 에 `mfc-location-service`.
- 그 밖 오류는 Problem Details(400 `sinceMinutes` 범위, 403 권한). 화면 약속대로 실패를 0명으로 바꾸지 말아 주세요.

## 권한
- 그 센터 `MFC_SUPERVISOR`, 또는 `OPERATOR_ADMIN`(모든 센터). 작업자(MFC_WORKER 만)·다른 센터는 403.
- 게이트웨이 경로 `/api/v1/mfcs/*/worker-positions` → mfc-capacity-service 추가(OPERATOR 허용 목록 포함).

## 칸 좌표 — 이미 등록돼 있음(2026-11-21 PC 1 확인)
- LAN MFC-SEED-01 의 쓰는 칸은 **모두 도면 좌표가 있다**: 선반 14,671칸·카트 60·포장 10·출고 대기 2·격리 3·수령 1·반품 1(가상 현장 평면 배치 — 실측 아님, `deploy/lan/seed/mfc_layout.py register·support`, 2026-11-09~). 좌표가 없는 79칸은 모두 CLOSED 옛 시험 칸이다.
- 그래서 실서버에서도 점이 찍힌다. 좌표 없는 칸에서 마지막 스캔한 작업자는 `items` 가 아니라 **`unplaced`(reason `NO_POSITION`)** 로 온다(옛 칸·새로 등록한 칸 등) — 점은 못 찍지만 "어느 칸에서 언제"는 보여 줄 수 있다.
- 배치가 바뀌거나 실측 도면·새 센터가 생기면 같은 스크립트를 그 배치 파일로 다시 돌린다(칸 코드로 맞춰 좌표만 덮어씀, 다시 돌려도 됨).
