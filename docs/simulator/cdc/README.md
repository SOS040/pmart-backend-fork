# CDC 시뮬레이터 (백엔드 쪽 문서)

아직 없음 — MFC 시뮬레이션을 완성한 뒤 그것을 토대로 만든다(사용자 2026-11-09, `../scope.md` S-2).

- 범위(`../scope.md` S-3·S-6): **출고·피킹·상차만**. 입고·재고 조정·품질·수요예측은 넣지 않는다. CDC 재고는 시작 재고(시드)로 둔다 — 확인 필요.
- 근거 업무 문서: `docs/business/WMS-business-logic.md`(CDC WMS). MFC 문서(`MFC-business-logic.md`)와 섞지 않는다.
- 백엔드 현황: CDC 쪽은 `cdc-inventory-service` 만 있다. 출고·피킹·상차를 맡을 `cdc-outbound`·`cdc-location` 은 없다(service-map). 이어지는 운송은 TMS(scope S-4·S-7 — 차량·기사 정보, 배차, 실제 출발·도착 기록).
- 도면: CDC 도 센터마다 다르다(ADR-0050 과 같은 원칙). 만들면 이 폴더에 `layout/` 을 둔다.
