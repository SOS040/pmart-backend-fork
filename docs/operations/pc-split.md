# PC 나눠 작업하기 — 영역·담당·기록 규칙 (2026-11-13)

여러 PC 가 같은 저장소를 동시에 고친다(사용자 결정 2026-11-12 PC 분담, 2026-11-13 "영역 나누기 해줘", 클라이언트는 "앱 단위로"). 같은 파일을 여러 PC 가 고치면 PR 을 합칠 때마다 충돌하고 누가 무엇을 했는지 섞이므로, **일지·handoff·소유를 영역 단위로 나눈다.** PC 담당은 바뀔 수 있어도 영역은 잘 안 바뀌기 때문에 PC 가 아니라 영역으로 나눈다.

## 1. 백엔드 영역

| 영역 | 지금 맡는 PC | 서비스·폴더 | 계약(`contracts/`) 주인 |
|---|---|---|---|
| **platform** | PC 1(192.168.0.32) — 배포 유일 지점 | auth-service·employee-auth-service·hr-service·approval-service·api-gateway·config·eureka·mail-provisioner, `libraries/`·`build-logic/`·`deploy/`, 그리고 아래 두 영역 밖의 모든 서비스(커머스·CDC·RAG 등 — 담당이 생기면 영역을 더한다) | 공통(`_common.yaml`·`_envelope`·`_defs`), 영역을 넘는 사건(`SubjectBlocked`·`ApprovalDecided`·`Employee*`·`GrantRequested` 등), platform 서비스 |
| **mfc** | PC 3(192.168.0.222) | mfc-inventory·mfc-fulfillment·mfc-capacity·mfc-location·mfc-quality·mfc-inbound-service, `docs/simulator/mfc/` | mfc 서비스 계약·mfc 안에서만 오가는 사건 |
| **delivery** | PC 5(192.168.0.101) | delivery-serviceability·delivery-capacity·delivery-dispatch·delivery-execution·delivery-tracking-service, rider-service, rider-auth-service | delivery 서비스 계약·delivery 안 사건 |

- 남의 영역 서비스·계약을 고쳐야 하면 직접 고치지 않고 `docs/architecture/service-requests/<받는 서비스>.md` 에 요청을 적는다(지금 규칙 그대로). 급하면 PR 을 열되 그 영역 담당이 리뷰한다.
- 영역을 넘는 사건·공통 계약은 platform 이 바꾼다(한 주인).
- 브랜치 이름 `<영역>/<주제>`(예 `mfc/count-variance`). PR → PC 1 이 합치고 배포한다.

## 2. 클라이언트 영역 (pmart-clients — 앱 단위)

| 영역 | 폴더 |
|---|---|
| **customer-app** | `apps/customer_app` |
| **mfc-app** | `apps/mfc_app` |
| **rider-app** | `apps/rider_app` |
| **backoffice** | `web/backoffice` |
| **hr-backoffice** | `web/hr-backoffice` |
| **web-common** | `web/core`·`web/ui`·`tools/web-tools`(두 웹앱 공통 — 한 주인) |

클라이언트 저장소 규칙은 클라이언트가 정한다 — 백엔드는 `docs/handoff/clients/2026-11-13-all-notice-area-split.md` 로 같은 방식을 제안했다.

## 3. 작업 일지 — 영역별 폴더

- **`docs/worklog/<영역>/YYYY-MM-DD.md`**(platform·mfc·delivery). 자기 영역 파일에만 쓴다. 2026-11-13 까지의 일지는 `docs/worklog/YYYY-MM-DD.md`(루트, 그대로 둔다).
- 다른 영역에 영향을 준 일은 자기 일지에 `영향: mfc` 처럼 적는다(남의 일지에 쓰지 않는다).
- 규칙은 그대로: 커밋 없이 일지만 쓰지 않고, 일지 없이 커밋하지 않는다(`docs/worklog/README.md`).

## 4. handoff — 파일 이름에 받는 앱(영역)

- 백엔드 → 클라이언트: `docs/handoff/clients/YYYY-MM-DD-<받는 앱 영역>-<종류>-<주제>.md`. 받는 앱 영역 = §2 의 이름, 여럿이면 `all`. 종류: request·reply·notice. 첫머리에 **`보낸 영역: platform|mfc|delivery`**.
  - 예: `2026-11-14-mfc-app-reply-worker-login.md`(보낸 영역 mfc), `2026-11-14-backoffice-notice-grant-approval.md`(보낸 영역 platform).
- 한 주제는 한 영역이 맡는다(같은 주제를 두 PC 가 따로 보내지 않는다). 인증·HR·배포·공통 계약은 platform.
- 2026-11-13 까지의 파일은 이름 그대로(`…-clients-…`).
- 시뮬레이터 handoff(`docs/handoff/simulator/`)는 mfc 영역이 맡는다.

## 5. 여럿이 고치는 표 — 합칠 때 PC 1 이 몰아서

`docs/architecture/service-requests/README.md`, `docs/business/flows/pending-user-decisions.md`, `docs/architecture/events.md`·`service-map.md` 의 표처럼 여러 영역이 고치는 목록은, 다른 PC 는 **개별 파일(요청 파일·자기 일지)만** 고치고 표 갱신은 PR 설명에 적는다. PC 1 이 합치면서 표를 갱신한다(충돌을 한 곳으로 모은다). 자기 영역 줄 하나만 고치는 것은 괜찮다.

## 6. 다른 PC 가 알아야 할 사실은 저장소 문서에

Claude 메모는 PC 마다 따로라 서로 모른다. 다른 PC 도 알아야 할 사실(계정·토큰 받는 법·바뀐 규칙)은 메모가 아니라 저장소 문서에 둔다 — `docs/operations/dev-on-other-pcs.md`(개발 방법), 이 문서(영역·기록 규칙). 루트 `CLAUDE.md` 가 두 문서를 가리킨다.
