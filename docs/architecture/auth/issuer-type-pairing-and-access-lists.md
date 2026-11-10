# 발급자 3개 — 경로는 그대로, "발급자 ↔ 사용자 종류" 짝으로 막기 + (나중) 허용 목록·차단 목록

상태: **경로 유지 + 발급자 짝 방식 결정(2026-11-10, ADR-0054)**. 허용 목록·차단 목록(§6)도 이번 작업 범위(사용자). 코드는 아직 없음 — 시작 전 결정은 ADR-0054 '정할 것'.

## 1. 한 줄 요약
경로(`/api/v1/<자원>`)는 바꾸지 않는다. 게이트웨이와 각 서비스는 ① 토큰의 `iss` 로 **어느 발급자인지** 고르고 그 발급자 공개키로 서명을 확인한 뒤, ② **그 발급자가 낼 수 있는 사용자 종류인지** 확인하고, ③ 서비스가 API 마다 사용자 종류·역할·소유권을 본다.

## 2. 발급자 표(설정 예시 — pmart-config `application.yml` 공통)
```yaml
pmart:
  security:
    issuers:                       # 이 목록에 없는 iss 는 무조건 401
      - id: staff                  # 직원 — Keycloak
        issuer-uri: http://keycloak:8080/realms/pmart-staff
        jwk-set-uri: http://keycloak:8080/realms/pmart-staff/protocol/openid-connect/certs
        audiences: [pmart-api]     # Keycloak 대상(audience) 매퍼로 넣음
        user-types: [OPERATOR]     # 이 발급자 토큰은 운영자로만 인정
        subject-claim: pmart_operator_id   # 기존 운영자 ID 유지(Keycloak sub=UUID 대신) — ADR-0054 정할 것
      - id: customer               # 고객 — 지금 auth-service 그대로
        issuer-uri: http://auth-service:8501
        jwk-set-uri: http://auth-service:8501/api/v1/internal/jwks
        user-types: [CUSTOMER, GUEST, SERVICE]
      - id: rider                  # 라이더 — 새 라이더 auth 서비스(Google 로그인)
        issuer-uri: http://rider-auth-service:<port>
        jwk-set-uri: http://rider-auth-service:<port>/api/v1/internal/jwks
        user-types: [RIDER]
```
- 사용자 종류는 **발급자 설정에서 정한다.** auth-service·라이더 auth 토큰에 있는 `typ` 클레임은 짝 확인에만 쓰고(목록 밖이면 거부), Keycloak 토큰처럼 `typ` 가 없으면 발급자의 종류(OPERATOR)를 쓴다.

## 3. 처리 순서
```
요청 + Bearer 토큰
  │
  ▼ [게이트웨이]
  1. 토큰의 iss 읽기(서명 확인 전에는 "어느 키로 확인할지" 고르는 데만 씀)
  2. 발급자 표에 없으면 401
  3. 그 발급자 JWKS 로 서명·만료·iss·aud 확인 → 실패 401
  4. 사용자 종류 결정: typ 클레임이 있으면 그 발급자 user-types 안이어야 함 → 아니면 401
  5. 지금 규칙 그대로: /internal 거부, SERVICE 는 외부 경로 거부, GUEST 는 비회원 경로만
  6. 라우팅(경로 → 서비스)
  │
  ▼ [서비스, common-security]
  7. 같은 1~4 를 다시(게이트웨이를 믿고 건너뛰지 않음) → TYPE_x 권한 + 역할 + 센터
  8. API 마다: @PreAuthorize("hasAuthority('TYPE_RIDER')") 등 + 업무 인가(배차 소유권, 라이더 승인, 센터 범위)
```

## 4. 예시
| 요청 | 토큰 | 결과 | 막는 곳 |
|---|---|---|---|
| `POST /api/v1/deliveries/D1/completions` | 라이더 auth 의 라이더 토큰, D1 담당 | 200 | — |
| 같은 요청 | auth-service 고객 토큰 | 403 | 서비스 8(`TYPE_RIDER` 아님) |
| 같은 요청 | 라이더 토큰, D1 담당 아님 | 403 | 서비스 8(배차 소유권) |
| 같은 요청 | auth-service 가 서명했는데 `typ=RIDER` 라고 적힌 토큰(있을 수 없지만) | 401 | 게이트웨이 4(auth-service 는 RIDER 를 낼 수 없음) |
| 같은 요청 | 목록에 없는 발급자(위조 Keycloak 등) | 401 | 게이트웨이 2 |
| `GET /api/v1/deliveries/D1/eta` | 고객 토큰(본인 주문) | 200, 도착 시각만 | 서비스가 종류별로 응답 범위 다르게 |
| 같은 요청 | Keycloak 직원 토큰(MFC 점원, 그 센터) | 200, 픽업 + 도착 | 서비스 8(센터 범위) |
| `GET /api/v1/assistant/...` | 고객 토큰 | 403 | 서비스(`TYPE_OPERATOR` 만) |

## 5. 코드 모양(예시, 실제 이름은 구현 때)
게이트웨이(WebFlux):
```java
// 발급자별 인증 관리자 선택 — 목록에 있는 iss 만
var managers = issuers.stream().collect(toMap(Issuer::uri, i -> {
  var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(i.jwkSetUri()).build();
  decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
      new JwtIssuerValidator(i.uri()), new AudienceValidator(i.audiences())));
  var m = new JwtReactiveAuthenticationManager(decoder);
  m.setJwtAuthenticationConverter(new IssuerBoundConverter(i)); // 4번: 종류 짝 + TYPE_x/역할/센터
  return m;
}));
http.oauth2ResourceServer(o -> o.authenticationManagerResolver(
    new JwtIssuerReactiveAuthenticationManagerResolver(iss -> Mono.justOrEmpty(managers.get(iss)))));
```
서비스(servlet, `common-security`): 같은 표로 `JwtIssuerAuthenticationManagerResolver`, 변환기는 `PmartJwtAuthenticationConverter` 를 발급자 설정을 받게 확장. 바뀌는 것은 이 공통 부분과 설정뿐이고, 서비스 컨트롤러·경로·계약은 그대로.

## 6. (나중 작업) 허용 목록·차단 목록
서명이 맞는 토큰이라도 **만료 전에 막아야 할 때**(퇴사, 라이더 정지, 토큰 유출)와 **경로마다 들어올 수 있는 사용자 종류를 게이트웨이에서 한 번 더 거를 때**를 위한 구조. 지금은 만들지 않는다.

### 6.1 허용 목록(화이트리스트)
| 무엇 | 내용 | 정본·관리 |
|---|---|---|
| 발급자 | §2 표 — 표에 없는 발급자는 거부 | pmart-config(위 설정 그 자체) |
| 경로별 사용자 종류 | 경로 + 메서드마다 들어올 수 있는 종류(예: `POST /api/v1/deliveries/*/completions` → RIDER). **목록에 없는 경로는 기본 거부** | 계약(OpenAPI)에 `x-pmart-user-types: [RIDER]` 를 적고 **빌드 때 목록을 자동 생성** → 게이트웨이 설정. 손으로 쓰지 않는다(빠뜨리면 정상 요청이 막힘) |
| 관리 경로 출발지 IP | 백오피스·관리 API 는 사내/LAN 대역에서만(선택) | pmart-config |
| 외부 웹훅 출발지 | 결제 웹훅(`/api/v1/webhooks/**`)은 PG 가 알린 IP 대역만 + 서명 확인 | pmart-config |
| 앱 클라이언트 | 토큰의 대상·클라이언트 ID(`aud`/`azp`)가 우리 앱 것인지 | 발급자 설정(`audiences`) |

### 6.2 차단 목록(블랙리스트)
| 무엇 | 언제 쓰나 | 형태 | 기간 |
|---|---|---|---|
| 토큰 하나(`jti`) | 유출된 토큰 하나만 막기 | `(발급자, jti)` | 그 토큰 만료까지 |
| 주체 전체 | 퇴사·계정 정지·라이더 정지 — 그 사람의 **이미 발급된 토큰 전부** | `(발급자, sub, 이 시각 이전 발급 토큰 무효)` — 토큰 `iat` 와 비교 | 가장 긴 토큰 수명까지(그 뒤엔 발급자가 새 토큰을 안 줌) |
| 출발지 IP | 공격·남용 | IP·대역 + 사유 + 만료 | 정한 시각까지 |

- **저장**: 게이트웨이가 이미 쓰는 Redis(처리율 제한용)에 둔다. 요청마다 조회해야 해서 빨라야 한다.
- **채우는 쪽(정본은 발급자·업무 서비스)**: 직원 = Keycloak(계정 비활성)·auth 관리 화면, 고객 = auth-service(계정 정지), 라이더 = 라이더 auth + rider-service(이미 있는 `RiderRestricted` 사건). 각자 "차단" 사건을 내고, 게이트웨이 쪽 구독자가 Redis 에 쓴다. 사람이 Redis 에 직접 쓰지 않는다.
  - **구현(2026-11-11, ADR-0054 결정 K)**: 사건 `SubjectBlocked`(계약 `contracts/events/SubjectBlocked.v1.json`, 토픽 `platform.subject-block.v1`, 키 = 주체) — payload `issuer`(토큰 iss 그대로)·`subject`·`userType`·`blockedBefore`·`reason`. 지금 발행: auth-service 운영자 비활성화·비밀번호 초기화, **Keycloak 직원**은 auth-service 가 Keycloak 관리 기록을 읽어 대신 낸다(`StaffBlockRelay`, ADR-0054 결정 M — 비활성·세션 로그아웃·비밀번호 재설정, 10초 주기, 사용자 삭제는 못 막음). 게이트웨이 함수 `subjectBlocks`(그룹 `api-gateway`) → Redis `pmart:gw:subject-block:<iss>|<sub>` = 차단 시각(epoch 초, 늦은 값만 남김, 남길 기간 = 차단 시각 + `pmart.gateway.subject-blocklist.max-token-lifetime`(2시간) − 지금). 판정 `iat <= 차단 시각`(초 단위, iat 없으면 차단). 해제 사건은 두지 않는다 — 다시 쓸 수 있게 되면 새로 받은 토큰이 통과한다.
- **확인 위치**: 게이트웨이 §3 의 3번(서명 확인) 바로 뒤. 차단이면 401 + 사유 코드(`TOKEN_REVOKED`·`SUBJECT_BLOCKED`·`IP_BLOCKED`).
- **Redis 장애 때**: 처리율 제한과 같이 **통과**(fail-open, ADR-0054 결정 H). 조회 상한 200ms. 지표 `pmart_gateway_subject_block_total{result}`(blocked·lookup_failed).
- **서비스는**: 업무 상태(라이더 정지 → 배차 제외, 작업자 LEFT → 새 배정 거부)를 지금처럼 자기 데이터로 본다. 차단 목록은 그 앞의 빠른 차단일 뿐 대신하지 않는다.
- **감사**: 차단 추가·해제는 누가·왜·언제를 audit 에 남긴다.

### 6.3 진행
- 2026-11-10 ① 발급자 표(105da93) ② 경로별 허용 목록 **RECORD 모드** — 계약 외부 작업 400개에 `x-pmart-user-types`(추정 94개는 `user-type-allowlist-estimates.md` 에서 검토), 생성 파일 `api-gateway/src/main/resources/access/user-type-allowlist.txt`(417줄), 필터 `UserTypeAllowlistFilter`. ENFORCE 전환은 추정 목록 확정 + LAN 에서 경고 로그가 없는지 본 뒤.

- 2026-11-11 ⑤ 주체 차단(위 6.2 구현). `jti`·IP 차단은 아직.

### 6.4 할 때 순서(제안)
1. 계약에 `x-pmart-user-types` 표시 → 목록 생성 도구 → 게이트웨이 "경로별 종류" 허용 목록(처음엔 기록만 하고 막지 않는 모드로 돌려 빠진 경로를 찾은 뒤 막기)
2. 주체 차단(퇴사·정지) — 가장 쓸모 있음
3. `jti` 차단·IP 차단
