# ADR-0006 인계 fence는 mfc-fulfillment, 취소 판정은 order-orchestration이 맡는다

- 상태: 승인 (2026-11-08, 사용자 결정 D-2 "그래" — 판정 주체 = mfc-fulfillment, 별도 판정 서비스 없음. 운영 허가 유효시간 기본 3분)
- 관련: MFC Q-01(공통 주문 판정 주체 미결), IV-08 L1425, 인계 절차 L1980~1986, OMS 8.3~8.4·A.1.11·A.5.4, BackOffice BO-12, Delivery 3.4·5.4

## 배경
MFC 문서는 "취소 접수와 실제 인계는 같은 수량에 대해 한쪽을 먼저 확정"해야 하며 `AuthorizePackageHandover`→`HANDOVER_IN_PROGRESS`(fence)→`CommitPackageHandover`(재고 커밋) 절차를 정의하지만, 이 "공통 판정 주체"가 어느 시스템인지(Q-01)는 미결이다. CLAUDE.md는 `mfc-fulfillment`에 "취소 작업 중지·실제 인계 절차"를, `order-orchestration`에 사가를 두었다. 동기 체인 2 hop 규칙도 지켜야 한다.

## 결정
1. **포장 단위 인계 fence의 정본 = `mfc-fulfillment-service`.** 라이더 QR 스캔 → gateway → `mfc-fulfillment.AuthorizePackageHandover`(배정 라이더·배송번호·포장번호 일치, 기인계·무효 라벨·제한 라이더 검사) → `HANDOVER_IN_PROGRESS` → `CommitPackageHandover` → `mfc-inventory.CommitPackageHandover`(H−q, E−q, R−q, commitId) → `PackageHandedOver`. 체인은 2 hop.
2. **취소의 사업 판정 = `order-orchestration-service`(S3)**, 접수·자격은 `claim-service`. orchestration은 `mfc-fulfillment.StopWork`를 호출해 포장별 `STOPPED / HANDOVER_IN_PROGRESS / HANDED_OVER` 판정을 받고, fence 중이면 결과를 기다린다.
3. 취소 접수시각은 우선순위 근거가 아니다. 현장이 먼저 확정한 결과가 유효하고, 후속 요청은 최신 상태로 재심사.
4. 인계 확정분은 출고 전 취소 불가 → S6 회수.
5. `delivery-execution`은 `PackageHandedOver` 이벤트로 픽업 완료를 반영한다(동기 호출 없음). GPS·버튼만으로 픽업 확정 금지.

## 대안
- 판정 주체를 orchestration에 두고 fence를 orchestration이 관리 — 현장 스캔마다 orchestration 왕복, 오프라인 PDA 처리 불가.
- 판정 주체를 `mfc-inventory`에 두기 — 재고 서비스가 포장·라이더·배정 정보를 알아야 해 경계 침범.

## 결과
- `WorkStopResult`와 `PackageHandedOver`는 같은 packageId에 대해 상호 배타. 둘 다 오면 발생시각이 아니라 fence 버전으로 판정, 충돌은 `ReconciliationRequired`.
- fence 만료는 "취소 성공"이 아니다. 만료 후 재조회로 실제 상태 확인.
