# 직원 계정 발급·정지·퇴사 흐름 (hr-service, ADR-0056)

- 작성: 2026-11-13. 구현: `services/hr-service`(`ProvisioningWorker`·`IamSyncWorker`·`ContractEndJob`), 계약 `contracts/openapi/hr-service.yaml`·`mail-provisioner.yaml`, 사건 `contracts/events/Employee*.v1.json`.
- 근거: ADR-0056 결정 5·7·8·10·16, 설계 `docs/architecture/auth/employee-auth-structure.md` §7-③·④, §8-1.
- 성격: 여러 시스템(사내 메일 서버·Keycloak·employee-auth-service)에 걸치는 흐름이지만 **분산 트랜잭션이 아니다**. hr-service 가 단계별 진행 상태를 자기 DB 에 남기고, 재시도는 멱등, 끝내 안 되면 이번에 만든 것만 비활성으로 되돌린다(보상).

## 1. 등록 → 계정 발급

```
인사팀 ─POST /hr/employees─▶ hr-service: 직원(OP-UUID·사번)·진행 REQUESTED·요청 권한(PENDING) 저장 → 201
                                  │ (응답 뒤 작업기, 한 명씩 행 잠금 — SKIP LOCKED)
REQUESTED ──▶ mail-provisioner: 사서함 만들기(멱등) ─▶ 메일 서버가 그 주소를 받는가(SMTP RCPT 250)?
              아니면 MAILBOX_NOT_READY 로 재시도(수 초) — 받기 전에는 안내 메일을 보내지 않는다
MAILBOX_CREATED ──▶ Keycloak(hr-provisioner): 사용자 만들기(username = email = <id>@pmart.com, pmart_operator_id, UPDATE_PASSWORD)
              이미 있으면(409) 운영자 ID 가 같을 때만 그대로(재시도 멱등), 다르면 IAM_USERNAME_TAKEN(영구 거절)
IAM_CREATED ──▶ Keycloak: 비밀번호 설정 메일(execute-actions-email, 3일) → 사내 메일 서버 → 그 사서함
INVITE_SENT ──▶ outbox: EmployeeRegistered(그 사이 정지·퇴사됐으면 그 상태 사건도 이어서) → DONE
                         └▶ employee-auth-service: Keycloak sub ↔ OP 연결, 로그인 상태(5-3단계)
```

| 실패 | 처리 |
|---|---|
| 일시 실패(메일 서버·Keycloak 응답 없음, 사서함 반영 대기) | 같은 단계에 남고 지수 대기(5초부터 두 배, 최대 10분)로 다시. 단계마다 횟수를 새로 센다 |
| 횟수 소진(8회) 또는 영구 거절(IAM_USERNAME_TAKEN·hr-provisioner 자격 거절·4xx) | FAILED → **보상**: 만든 Keycloak 사용자 비활성, 사서함 비활성(지우지 않는다). 다 되면 COMPENSATED |
| 다시 시작 | 인사팀 `POST /hr/employees/{id}/provisioning-retries`(FAILED·COMPENSATED 만) → REQUESTED. 이미 있는 사서함·Keycloak 사용자는 그대로 이어 쓴다 |

- 요청 권한은 이 흐름과 따로다 — approval 에 권한 요청으로 제출(5-4단계 연동 뒤), 승인되면 employee-auth-service 가 저장. 계정이 있어도 승인 전에는 업무 API 403.
- 사서함(웹메일) 비밀번호는 등록 응답에 없다. 인사팀이 `mailbox-password-resets` 로 받아 따로 전달(응답에만, 저장·재생 없음). Keycloak 비밀번호는 본인이 메일 링크로 정한다 — 두 비밀번호는 맞추지 않는다.

## 2. 정지·복귀·퇴사·계약 종료

```
인사팀 ─POST …/suspensions | /reinstatements | /departures─▶ hr-service (같은 트랜잭션)
    인사 상태 변경 + 변경 기록 + 사건(EmployeeSuspended·Reinstated·Left, 발급이 끝난 직원만) + "Keycloak·사서함 맞추기" 대기
        ├▶ employee-auth-service(사건): 로그인 상태·세션 종료·SubjectBlocked(즉시 차단) — 5-3단계
        └▶ IamSyncWorker(재시도): Keycloak enabled=상태, 정지·퇴사면 Keycloak 세션 로그아웃, 퇴사면 사서함 비활성, 복귀면 다시 활성
ContractEndJob(한 시간마다): 계약 종료일(Asia/Seoul)이 지난 재직 중 직원 → 정지(CONTRACT_ENDED) — 위와 같은 길
```

- 즉시 차단은 사건 쪽(employee-auth-service → 게이트웨이 차단 목록)이 맡는다. Keycloak 이 잠깐 꺼져 있어도 인사 처리는 막히지 않고, Keycloak 반영은 늦게라도 된다.
- 퇴사해도 행·사번·메일 ID·사서함을 지우지 않는다. 메일 ID 는 다시 쓰지 않는다. 재입사는 같은 OP·사번으로 `reinstatements`.
- 발급 중(DONE 전)에 정지·퇴사하면 사건을 바로 내지 않고, 발급이 끝날 때 EmployeeRegistered 뒤에 지금 상태 사건을 낸다(버전 하나 올림).

## 3. Keycloak 관리 자격(결정 16)·U-11

- hr-service 는 서비스 계정 클라이언트 `hr-provisioner`(`client_credentials`, 토큰 캐시, 401 이면 한 번 새로 받아 재시도)로 **사용자 경로만** 부른다 — 만들기·찾기·활성/비활성·세션 로그아웃·비밀번호 설정 메일.
- Keycloak 클라이언트는 `fullScopeAllowed: false` 이므로 `realm-management` 역할을 **클라이언트 범위 매핑**으로도 줘야 토큰에 실린다(시험에서 확인 — 빠지면 Admin API 403).
- **U-11 확인(2026-11-12, Keycloak 26.8.1, `HrProvisioningIT`)**: `manage-users` 만 가진 `hr-provisioner` 는 Realm 역할을 **읽지는 못하지만(403) 사용자에게 Realm 역할을 매핑할 수는 있다(204)**. 그래서 (B) hr-service 는 역할 매핑 API 를 부르지 않고, (A) 발급자 표의 Keycloak 줄(Keycloak 토큰의 `realm_access.roles` 를 권한으로 믿는 줄)을 hr-service LAN 배포 **전에** 뺀다.
