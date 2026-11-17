# [pmart-backend → pmart-clients] 요청: 인사팀 앱 권한 요청 — 센터 칸·이미 가진 권한·새 거절 코드 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 받는 앱: hr-backoffice(`web/hr-backoffice`) — 담당 PC 2
- 근거: 권한 빈틈 `docs/architecture/auth/permissions.md` §9-11·12, 요청서 `docs/architecture/service-requests/hr-service.md` HR-2. 사용자 결정 2026-11-17 "백엔드부터 일단 내쪽(PC 1)에서 작업하고 클라이언트한텐 그 요청서로 보내자".
- 계약: hr-service **0.6.0**(`contracts/openapi/hr-service.yaml` — info.description 0.6.0, `registerEmployee`·`requestEmployeeGrants`), employee-auth-service 0.11.0(내부 조회만 — 화면이 부르는 API 는 그대로).
- 백엔드 상태: 구현·시험 끝, **LAN 배포 전**(배포하면 PC 1 일지에 적는다). 화면은 먼저 만들어도 된다 — 배포 전에는 서버가 아직 이 검사를 하지 않을 뿐.

## 왜
지금은 이미 가진 권한을 또 요청할 수 있고(승인되면 같은 권한 줄이 둘 — 하나만 회수하면 권한이 남는다), 한 요청에 같은 역할을 두 줄 넣거나, 센터가 필요 없는 역할에 센터를 넣거나, 센터 작업자·관리자에 센터 없이 요청할 수 있었다. 서버가 이제 거절하므로 화면도 미리 막고, 거절이 오면 알아볼 수 있게 보여 달라.

## 1. 센터 칸 — 역할 목록의 `centerScoped` 대로 (등록 "요청 권한"·권한 관리 "권한 추가·변경" 둘 다)
- 역할 목록 `GET /api/v1/employee-auth/roles`(이미 쓰는 API)의 `items[].centerScoped`:
  - **true**(지금 `MFC_SUPERVISOR`·`MFC_WORKER`) → 권한 센터 **필수**(1개 이상). 비어 있으면 제출 버튼을 끄거나 "센터를 1개 이상 고르세요".
  - **false**(그 밖 전부) → 센터 칸을 **숨기거나 끄고**, 역할을 바꿀 때 이미 고른 센터는 비운다(보내지 않는다).
- 역할 코드를 화면에 고정하지 말고 이 값으로 판단한다(역할이 늘면 설정만 바뀐다).

## 2. 한 요청에 역할은 한 번
- 같은 역할을 두 줄 넣지 못하게(역할 고르기에서 이미 고른 역할을 빼거나 끈다). 여러 센터는 한 줄의 `centers` 에 모은다(예: `MFC_WORKER` + 센터 A·B 한 줄).

## 3. 이미 가진 권한 보여 주기 (권한 관리 "권한 추가·변경")
- 직원 조회 `GET /api/v1/employee-auth/employees/{operatorId}`(이미 쓰는 API)의 `grants[]` 중 **끝나지 않은 것** — `validUntil` 이 없거나 지금보다 뒤(회수된 것은 `validUntil` = 회수 시각이라 자연히 빠진다) — 을 요청 화면 가까이에 "지금 가진 권한 / 예정된 권한"으로 보여 달라: 역할 이름·센터·시작(`validFrom`)~끝(`validUntil` 의 전날이 마지막 날, 없으면 "기한 없음").
- 서버 판정(참고 — 화면은 미리 경고만 해도 된다): **역할이 같고, 센터가 겹치고(센터가 필요 없는 역할은 센터와 상관없이), 기간이 지금 이후로 겹치면** 이미 가진 권한이다.
  - 받는 경우: 기존 권한이 **끝난 날부터** 시작하는 요청(예: 기존 마지막 날이 12-31 이면 시작일 01-01 부터), 다른 센터.
  - 요청 기간을 비우면 서버는 근무 기간(시작일 ~ 계약 종료일, 정규직은 기한 없음)으로 본다 — 정규직에게 기한 있는 권한이 있으면 빈 기간 요청은 대개 겹친다.
- 화면 제안: 겹치는 역할을 고르면 "이미 가진 권한입니다(…까지). 끝난 다음 날부터로 요청하거나, 필요 없으면 먼저 회수하세요" 안내.

## 4. 기간 연장은 아직 없음
- 이미 가진 권한의 **끝나는 날을 늘리는 것(연장)** 은 별도 기능이 없다 — 겹치면서 더 늦게 끝나는 요청도 409 로 거절된다(연장을 새 요청으로 받을지 "연장" 요청으로 나눌지 **사용자 결정 대기**). 화면에 "연장" 버튼을 만들지 말고, 거절 메시지를 그대로 보여 달라. 지금 할 수 있는 길: 기존 권한이 끝난 다음 날부터 새로 요청.

## 5. 새 거절 코드 (Problem Details — `code`·`detail`·`details[]`)
| HTTP | code | 언제 | `details[].field` | 화면 |
|---|---|---|---|---|
| 400 | `VALIDATION_FAILED` | 한 요청에 같은 역할 두 줄 | `grants`(등록은 `requestedGrants`) | 해당 목록에 `detail` 표시 |
| 400 | `VALIDATION_FAILED` | 모르는 역할 | `grants[i].role`(등록 `requestedGrants[i].role`) | i 번째 줄 역할 칸 |
| 400 | `VALIDATION_FAILED` | 센터 규칙 — 센터 지정 역할에 센터 없음 / 센터가 필요 없는 역할에 센터 있음 | `grants[i].centers`(등록 `requestedGrants[i].centers`) | i 번째 줄 센터 칸 |
| 409 | `GRANT_ALREADY_ACTIVE` | 이미 유효하거나 시작 전인 같은 권한(연장 포함) — **권한 추가·변경 요청만**(등록은 새 직원이라 없음) | `grants[i]` — 겹친 기존 권한 하나당 한 줄 | i 번째 줄에 `detail`, 아래 reason 으로 기존 권한 강조 |
| 503 | `DEPENDENCY_UNAVAILABLE` | 서버가 역할 목록·지금 권한을 읽지 못함(검사 없이 받지 않는다) — 요청 권한이 있는 등록도 | — | "잠시 뒤 다시 시도" (같은 Idempotency-Key 로 다시 보내도 된다 — 거절은 기록되지 않음) |

- 409 `details[].reason` 형식(기계가 읽을 수 있게): `existingGrantId=<grantId>;role=<역할>;centers=<A,B>;validFrom=<UTC ISO>;validUntil=<UTC ISO|none>;extension=<true|false>`. `validUntil` 은 이 시각부터 무효(마지막 날 = 서울 날짜로 그 전날). `extension=true` 면 연장 요청이라 거절된 것(§4). `existingGrantId` 는 직원 조회 `grants[].grantId` 와 같아 그 줄을 찾아 강조할 수 있다.
- `detail`(사람이 읽는 문장)은 한국어로 그대로 보여 줘도 된다. 예: "이미 유효하거나 시작 전인 같은 권한이 있습니다: MFC_WORKER@MFC-01 (2026-11-17 ~ 2026-12-31). 그 권한이 끝난 다음 날부터로 요청하거나, 필요 없으면 먼저 회수하세요. 기간 연장(종료일 늘리기)은 아직 별도 기능이 없습니다(결정 대기)."
- 기존 거절(`GRANT_REQUEST_PENDING`·`INVALID_STATE`·403 자기 권한)은 그대로.

## 6. 승인함(backoffice) — 참고, 바꿀 것 없음
같은 권한이 어떻게든 승인돼도 employee-auth-service 가 권한 줄을 새로 만들지 않고 반영 회신에 그 행을 `REJECTED`(reasonCode `GRANT_ALREADY_ACTIVE`)로 남긴다(안전망). 승인함의 반영 결과가 PARTIAL·REJECTED 로 보일 수 있다.

## 회신
화면 반영 뒤 `pmart-clients/docs/handoff/hr-backoffice/` 회신 또는 PR 본문으로(항목 1~5 별 수용·대안·보류). 기간 연장 결정이 나면 PC 1 이 따로 알린다.
