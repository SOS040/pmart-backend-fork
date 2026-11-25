보낸 영역: platform(PC 1 — cdc)
받는 앱: cdc-backoffice
원 문서: pmart-clients `docs/handoff/cdc-backoffice/2026-11-25-backend-monitoring-gaps.md`(PR #566)
작성: pmart-backend, 2026-11-25
상태: M-3·M-6 **LAN 배포 끝**(2026-11-25 main 260f4bca), M-1·M-4 **구현(cdc-outbound 0.18.0) — LAN 배포 전**, M-2 cdc-inbound 뒤, M-5 설계 필요

# 회신: DC 백오피스 관제 빠진 조회

- **M-3 출발 준비·검증 결과(cdc-outbound 0.16.0)**
  - `GET /cdcs/{cdcId}/departures?result=&page=&size=` — 이 센터 TRF 가 든 출발 검증, 검증 시각 최신 순, result = MATCHED·PARTIALLY_MATCHED·MISMATCHED, 쪽 나눔은 transfers 와 같음. 줄 = `DepartureValidation`(내부 조회와 같음 — overallResult·transfers[] 결과·postingStatus·residualStatus·residualReason·mismatches[] 종류·LPN·사유).
  - `GET /cdcs/{cdcId}/transfers/{trfId}/readiness` — 출발 준비 단계(NOT_STARTED…LOADED·DEPARTED·CANCELLED)·검품 합격 BOX 수·수량·무게·온도대·blockers. 다른 센터 TRF 는 404.
  - 계약 바뀐 점: 출발 준비 응답 스키마 이름이 `OutboundReadiness`(전 inline — 생성 타입 이름 GetOutboundReadiness200Response → OutboundReadiness).
  - 운송(차량·기사·운행)은 TMS 서비스(transport-*·fleet)가 생긴 뒤.
- **M-6 칸별 재고(cdc-inventory 0.9.0)**: `GET /cdcs/{cdcId}/location-stocks?locationPrefix=&sku=&limit=` — 한 줄 = 칸 × SKU × LOT × 품질 상태(onHand·reserved·expiresAt), 칸 코드 → SKU → LOT 순, limit 기본 500·최대 2000, 넘으면 truncated=true. 칸 카드는 locationPrefix 로 구역·층·통로 단위로 부르세요 — 칠곡 칸 코드는 `CDC01-<구역>-<층>-<통로>-…`(예 `CDC01-A-5-01-01-1-1`)라 `CDC01-A-5-` = A 구역 5층, `CDC01-A-5-01-` = 그 통로. cdc-location 의 occupiedQty 는 그대로 비어 있어요(칸 재고는 이 조회로).
- **M-1 작업자 위치(cdc-outbound 0.18.0, Flyway V12)** — 사용자 결정 "PDA로 스캔한 위치":
  - 위치가 남는 때: 피킹 실적(`pick-results`)의 **마지막 줄 locationCode**, 그리고 새 `POST /cdcs/{cdcId}/workers/{workerId}/location-scans {locationCode, taskId?}`(작업자 본인 토큰, 204 — PDA·시뮬레이터 가상 작업자가 위치 바코드를 찍을 때). 작업자마다 마지막 한 줄(이력 없음).
  - `GET /cdcs/{cdcId}/workers?floor=` → `{asOf, workers[{workerId, locationCode, zone, floor, source(PICK_RESULT·SCAN), scannedAt, locationState, taskId, taskKind, taskState}], byFloor[{key=층, count}]}`. 위치가 있거나 지금 작업(ACCEPTED·IN_PROGRESS)을 든 작업자. locationState FRESH(10분 안)·STALE(10분 넘음 — "모름")·UNKNOWN(스캔 없음). byFloor 는 FRESH 인원만. 층·구역 = 칸 코드 셋째·둘째 구간.
- **M-4(0.18.0)**: `outbound-summary.byMfc[]` — 그날 MFC 별 `{mfcId, transferCount, approvedQty, allocatedQty, pickedQty, shortQty}`, MFC 순.
- **M-2** cdc-inbound 서비스가 생긴 뒤. **M-5 사건 기록**은 지금 cdc-outbound 가 낸 사건을 따로 쌓는 곳이 없어 표를 새로 두어야 해요 — 순서가 오면 따로.
