# PC 1 백엔드 세션 인수인계 — 2026-11-26 저녁

다른 계정으로 이어서 작업한다. 이 문서 하나로 이어 갈 수 있게 적었다. 앞선 인수인계: `2026-11-26-pc1-session-handoff.md`.

## 0. 먼저 읽을 것
- 사용자 고정 지시(그대로): 한국어로만, 서브에이전트 금지, 무거운 시험 전 메모리 확인(4GB 아래면 미룸), **push·배포는 사용자가 그때마다 말할 때만**, 비밀번호·토큰은 문서·대화에 쓰지 않음(파일 경로만), `services/payment-service/**`·`deploy/lan/monitoring/**`·`.claude/worktrees/` 의 커밋 안 된 변경은 커밋·삭제 금지(다른 작업), pmart-video 저장소는 커밋 금지, "검토해줘"는 답만.
- OR-Tools 쓰는 서비스(delivery-dispatch, mfc-capacity 등)는 `sh tools/ortools-test-docker.sh <task>` 로만 시험.

## 1. 사용자가 이번에 준 일 (2026-11-26 저녁, 사용자 결정 포함)
사용자 원문: "1. 주문 취소와 예약 해제(환불 포함)해 2. 해결해 3. 주문 넣고 배차 20분간 안 잡히면 자동 취소환불해 4. 밀린 것도 해결해 5. 구현해 6. 자동보충 만들어 7. 실시간으로 볼 수 있게 해" + "마감세일 PC 1 하지말자".
질문에 대한 답(모두 추천안): 3 = **배차 시작(배차 요청) + 20분**, 5 = **전부 여기서(PC 1)**, 6 = **추천 자동 발주 + 센터 관리자 승인 → DC 이관 요청**, 7 = **DC·MFC 백오피스 둘 다**.

| # | 일 | 상태 |
|---|---|---|
| 1 | 부산진구 옛 주문 3건(11-05·06, 출고 사가 없음) 취소·예약 해제·환불 | **끝(LAN)** — claim 시스템 취소 `FULFILLMENT_REJECTED`, 환불 19,100·79,140·28,300 SUCCEEDED, 예약 RELEASED(재고 23 풀림) |
| 2 | 재배송 사가 ESCALATED 1건 `SAGA-R-36a2f16c…` | **조사 끝** — 이미 11-20 담당자가 MARK_RESOLVED 해 둠(할 일 없음). 원인은 아래 §3 "남은 결정" |
| 3 | 배차 불가 자동 취소 = 배차 요청 + 20분 | **코드·시험 끝, 커밋 `c8502a6c7`, push·배포 전** |
| 4 | Kafka 밀린 메시지(빈 Bus 임시 그룹) | **끝(LAN)** — 빈 `anonymous.*` 3개 삭제, lag 0 확인 |
| 5 | IV-8·MI-5·CO-1·CO-2 | **코드·시험 끝, 커밋 3개, push·배포 전** |
| 6 | 자동 보충 | **코드 작성 중 — 커밋 안 됨, 시험 안 돌림**(사용자가 시험 실행 도중 멈춤) |
| 7 | MFC·DC 재고 실시간 보기 | **시작 안 함** |

## 2. push·배포 전인 커밋
pmart-backend(main, origin 보다 앞섬):
- `d8cf5de06` README 지도 교사 줄 삭제(사용자 요청)
- `c8502a6c7` delivery-dispatch 0.18.1 — 자동 취소 기준 `created_at + auto-cancel-after(PT20M)`(전에는 약속 + 30분). 시험 Docker 로 통과.
- `be3a44fab` mfc-inventory 0.52.0 IV-8 — 수령 확정 본문 `fromCdc` 있으면 `MfcReceiptPosted`(mfc.inventory.v1). mfc-inbound 가 fromCdc 실음. 시험 통과(`StockReservationFlowIT.cdcTransferReceiptPublishesMfcReceiptPostedOnce`, `InboundPromiseIT`). `ReceiptCorrectionPosted` 는 정정 기능이 없어 아직.
- `b83307eb3` mfc-inbound 0.22.0 V15 MI-5 — 재고 반영(TIN_POSTED) 때 자동 종결, 관리자 `POST /inbound-promises/{trfId}/closures`(→ CLOSED), cdc-inventory `/internal/transit-closures` 호출·재시도. 시험 통과.
- `67ad6b27e` cdc-outbound 0.30.0 CO-1·CO-2 — TRF 응답·매니페스트 `labelValue`(`TRF:{trfId}:{trfVersion}`), 박스 바코드 = LPN 값 그대로, 매니페스트 줄 `unitType`·`parentLpn`·`containers`, mfc-inbound `packages[].parentLpn`. 시험 통과(`OutboundFlowIT`, `InboundPromiseIT`). 입수(unitsPerBox)는 아직.

pmart-config(`deploy/lan/.config-repo`): `463d4f4` mfc-inbound `cdc-inventory-base-url`(lan·local).
조직 대문(`code-and-pick/.github`, 이 PC 임시 클론 — 세션 scratchpad `org-dotgithub`): `bb392a6` 지도 교사 줄 삭제 + 개발 기간 옆 일정. **임시 폴더라 사라졌으면 새로 클론해 같은 수정**(profile/README.md 맨 아래 "지도: 서성원 훈련 교사" 묶음 삭제, 개발 기간 "2026.10.23 ~ 진행 중 (10.23 서비스 뼈대 → 그다음 주 API 게이트웨이·LAN 배포)").
pmart-clients README 의 같은 줄은 고객앱 세션("PC1 프론트")이 지웠고 **그 세션에서 커밋 대기**(사용자가 그 세션에 커밋 지시 필요).

배포할 때(사용자가 말하면): pc5 delivery-dispatch, pc3 mfc-inventory·mfc-inbound·cdc-outbound(+ pmart-config push 먼저, mfc-inbound V15·V16 Flyway). jib 전 `deploy/lan/.secrets/registry.env` source.

## 3. 6번 자동 보충 — 하다 만 곳 (커밋 안 됨)
설계(사용자 "추천 자동 발주 + 승인"):
1. **자동 초안** `application/AutoSupply`: 매일 06:00 Asia/Seoul(`pmart.inbound.auto-supply-cron`, 켜기 `pmart.inbound.auto-supply-enabled` — 기본 false) 인계 실적 있는 센터마다 최근 28일 판 SKU 의 발주 추천(`SupplyRecommendations.compute` — 권한 검사 뺀 계산으로 분리함) 추천 수량 > 0 줄로 공급 요청 SUBMITTED(`submitted_by` SYSTEM, ref `AUTO-<센터>-<yyyyMMdd>` 하루 한 번, 미해결 요청 SKU 는 뺌 — `SupplyRequestService.createSystem`). 수동 실행 `POST /internal/auto-supply-runs {mfcId?}`.
2. **승인**: 기존 센터 관리자 승인 그대로. `pmart.inbound.supplying-cdc`(예 CDC01)가 있으면 initial_stock_load 대신 `InboundPromises.expectFromSupply`(입고 예정 EXPECTED, trfId `TRF-<요청 ID>`, fromCdc, 기한 승인 + `supply-due-after` P1D) + `supply_request.linked_trf_id·transfer_*` → 승인 응답 직후 `AutoSupply.send` 가 cdc-outbound `POST /internal/transfer-intakes`(`CdcTransferIntakeClient`, 키 `mfc-inbound-supply-trf:<trfId>`), 실패는 `sendDue`(PT15S) 재시도·400/409/422 는 멈춤. 비어 있으면 예전 동작(MI-4).
3. 그 뒤는 기존 흐름: CDC 할당·피킹·출발 → `TransferDeparted` → 같은 trfId 입고 예정에 출발 줄 → 도착·수령 → IV-8 사건 → MI-5 종결.

고친·만든 파일(작업 트리, 커밋 전):
- 계약 `contracts/openapi/mfc-inbound-service.yaml` 0.23.0(설명, `/internal/auto-supply-runs`, SupplyRequest `transferCdcId·transferDueAt·transferSentAt·transferError`)
- `V16__auto_supply_and_cdc_transfer.sql`(supply_request transfer_* 칸, SYSTEM ref unique 색인)
- `application/AutoSupply.java`(새), `infrastructure/CdcTransferIntakeClient.java`(새), `InboundProperties`(cdcOutboundBaseUrl 기본 `http://cdc-outbound-service:8603`·supplyingCdc·supplyDueAfter·autoSupplyEnabled), `InboundPromises.expectFromSupply`, `SupplyRequestService`(Row 칸 4개·approve 분기·createSystem·transfer 메서드), `SupplyRecommendations.compute`, `SupplyRequestsController`(승인 뒤 send·dto), `InternalController.runAutoSupply`
- 시험 `src/integrationTest/.../AutoSupplyIT.java`(새) — **아직 한 번도 안 돌림**

`compileJava` 는 통과했다. 남은 일:
1. `./gradlew :services:mfc-inbound-service:test :services:mfc-inbound-service:integrationTest`(메모리 먼저) — `AutoSupplyIT` 와 기존 `SupplyOrderIT`(보급 DC 없음 = 예전 동작) 둘 다 통과 확인. AutoSupplyIT 의 추천 수량이 0 이면 정책 시드('*' AMBIENT R1·L1·SS1)·가용 0 stub 을 확인.
2. pmart-config: `mfc-inbound-service-lan.yml` 에 `cdc-outbound-base-url: http://cdc-outbound-service:8603`(같은 pc3), `supplying-cdc: CDC01`, `auto-supply-enabled: true`(LAN). local 은 localhost:8603.
3. **결정할 점(사용자에게 확인 권장)**: 보급 DC 를 켜면 MI-4 센터 발주 승인(시뮬레이터 R-48 이 쓰는 "승인 → 바로 입고 예정")이 "DC 이관 → 출발해야 입고"로 바뀐다. LAN 에 켤지, 센터별로 할지.
4. ADR-0091 문서(`docs/adr/0091-auto-supply-and-cdc-transfer.md`) 쓰기 + `docs/business/flows/forecast-based-supply-request.md`·mfc-inbound CLAUDE.md·service-map(SC-1 일부 대체) 갱신, 그 뒤 커밋.
5. DC 쪽 재고가 없으면 cdc-outbound 인수는 ALLOCATION_FAILED — LAN 에서 CDC01 재고 있는 SKU 로 확인.

## 4. 7번 실시간 재고 — 시작 안 함
결정: DC·MFC 백오피스 둘 다. 서버 할 일(제안): cdc-inventory 재고 변동 SSE(`/cdcs/{cdcId}/stock-stream` — mfc-inventory IV-7 `StockStreams` 와 같은 모양, 5초 heartbeat, 게이트웨이 라우트·user-type allowlist 재생성), 운송 중 수량 조회(cdc-inventory Transit — TRF·출발·수령 r·미정산 t, MFC 쪽은 입고 예정 EXPECTED·RECEIVING 합). 화면 요청서 두 개: `docs/handoff/clients/cdc-backoffice/`·`docs/handoff/clients/mfc-backoffice/`(실제 폴더 이름 확인), 받는 세션 알림.

## 5. 다른 남은 것
- **재배송 두 번째 오배송**: 재발송 배송이 다시 오배송돼 RESHIP 을 고르면 `NOTHING_TO_RESEND` 로 멈춘다(보낼 수량 = 원수량 − 취소 − 이미 재발송 → 0). 11-20 사례는 11-24 REFUND 로 해결됐다. 두 번째 재발송을 허용할지(재발송 수량 한도) 또는 RESHIP 선택을 막고 REFUND 로만 할지 **사용자 결정 필요** — `ReshipmentSagaService.resolve`, `docs/business/flows/misdelivery-recovery-reshipment.md`.
- **고객앱 새 요청 BR-112·BR-113**(PC1 프론트 세션, 받음 확인만 보냄): 요청서 pmart-clients `docs/handoff/customer-app/2026-11-26-backend-sale-rate-filter-and-click-ranking.md`(그 세션 작업 폴더, 커밋 전). BR-112 search `minDiscountRate`(Q1 이상만으로 되나·구간 필요?), BR-113 최근 N시간 상세 조회 많은 상품(Q2 서버가 셀 수 있나·앱이 이벤트 보내야 하나). 회신은 `docs/handoff/clients/customer-app/`.
- **고객앱 1+1 화면**: 그 세션은 사용자 화면 레퍼런스를 받은 뒤 시작, 시험 행사는 그때 요청 온다.
- **마감세일 PC 1 쪽(promotion·pricing·order)**: 사용자 "하지말자" — 하지 않는다(PC 3 IV-9 는 이미 구현 #539).
- `heeji-test` 계정: 오늘 HR_ADMIN(승인 절차 P2026-00004 — 요청 claude-approver, 승인 claude-dev)·MFC 다섯 센터 추가 완료. 문서 `docs/operations/lan-test-accounts.md` 에는 아직 안 적음.
- 앞선 인수인계의 남은 것(Kakao 닉네임 재확인 — 사용자가 연결 끊고 다시 로그인 후, JMeter 부하 시험, 조직 대문 "MFC 앱 … DC 직원 현장 작업" 줄 답 대기)은 그대로.
- 서비스 요청 표·PC 3 할 일: IV-8·MI-5·CO-1·CO-2 를 PC 1 이 대신 구현했으니 PC 3·CDC 세션에 알릴 것(세션 목록에 없었음 — 사용자 전달 필요). `docs/operations/other-pcs-todo.md` 의 해당 줄 체크.
- 작업 일지 `docs/worklog/platform/2026-11-26.md` 에 오늘 저녁 내용(§1~§3) 아직 안 적음.
