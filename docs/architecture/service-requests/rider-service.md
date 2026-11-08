# rider-service 에 대한 요청 (MFC·배차 쪽에서)

rider-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다.

## RS-1. 라이더 단계 사건 발행 (D-4, 2026-11-08 결정 "추천대로")

- **요청한 곳**: delivery-dispatch(배정 풀기의 원천), mfc-fulfillment(간접).
- **필요한 것**: `RiderStateChanged`/`RiderRestricted` — 단계: 신규배차 제한 / 일시 중지 / 계약 종료. dispatch 가 일시 중지·계약 종료 라이더의 배정을 풀고(`DeliveryAssignmentChanged`) 재배정한다(DD-1 4번).
- **설계상 그대로 둘 것**: MFC 는 라이더 단계를 직접 보지 않는다 — `DeliveryAssignmentChanged` 로만 막는다.
- 라이더 앱의 QR → 인계 허가 → 확정 흐름은 rider 앱·delivery-execution(DE-1) 쪽.
- 근거: `docs/business/flows/package-handover-contract.md`, `package-handover-decisions.md` D-4.
