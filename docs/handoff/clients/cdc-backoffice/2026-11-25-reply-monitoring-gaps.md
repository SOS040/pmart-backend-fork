보낸 영역: platform(PC 1 — cdc)
받는 앱: cdc-backoffice
원 문서: pmart-clients `docs/handoff/cdc-backoffice/2026-11-25-backend-monitoring-gaps.md`(PR #566)
작성: pmart-backend, 2026-11-25
상태: M-3·M-6 **구현 — LAN 배포 전**(사용자 확인 뒤), M-1 결정 확인 중, M-2 cdc-inbound 뒤, M-4·M-5 다음

# 회신: DC 백오피스 관제 빠진 조회

- **M-3 출발 준비·검증 결과(cdc-outbound 0.16.0)**
  - `GET /cdcs/{cdcId}/departures?result=&page=&size=` — 이 센터 TRF 가 든 출발 검증, 검증 시각 최신 순, result = MATCHED·PARTIALLY_MATCHED·MISMATCHED, 쪽 나눔은 transfers 와 같음. 줄 = `DepartureValidation`(내부 조회와 같음 — overallResult·transfers[] 결과·postingStatus·residualStatus·residualReason·mismatches[] 종류·LPN·사유).
  - `GET /cdcs/{cdcId}/transfers/{trfId}/readiness` — 출발 준비 단계(NOT_STARTED…LOADED·DEPARTED·CANCELLED)·검품 합격 BOX 수·수량·무게·온도대·blockers. 다른 센터 TRF 는 404.
  - 계약 바뀐 점: 출발 준비 응답 스키마 이름이 `OutboundReadiness`(전 inline — 생성 타입 이름 GetOutboundReadiness200Response → OutboundReadiness).
  - 운송(차량·기사·운행)은 TMS 서비스(transport-*·fleet)가 생긴 뒤.
- **M-6 칸별 재고(cdc-inventory 0.9.0)**: `GET /cdcs/{cdcId}/location-stocks?locationPrefix=&sku=&limit=` — 한 줄 = 칸 × SKU × LOT × 품질 상태(onHand·reserved·expiresAt), 칸 코드 → SKU → LOT 순, limit 기본 500·최대 2000, 넘으면 truncated=true. 칸 카드는 locationPrefix(예 `CDC01-C-05-`)로 층·통로 단위로 부르세요. cdc-location 의 occupiedQty 는 그대로 비어 있어요(칸 재고는 이 조회로).
- **M-1 작업자 위치**: 전해 준 결정("PDA 로 스캔한 위치")은 PC 1 사용자에게 한 번 확인한 뒤 설계·계약을 알려 줄게요.
- **M-2** cdc-inbound 서비스가 생긴 뒤. **M-4 byMfc·M-5 사건 기록**은 다음 차례.
