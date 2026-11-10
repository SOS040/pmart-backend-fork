# ADR-0011 서비스 신원 토큰: auth-service 가 client_credentials 로 발급, /internal 호출에만 사용

- 상태: 일부 대체됨 by ADR-0026 (2026-10-31) — 서비스 토큰 발급·호출 측 서비스 토큰·/internal TYPE_SERVICE 토큰 요구는 폐지, 서명키·JWKS 는 유지. 원래: 제안 (2026-10-23)
- 관련: architecture-design §10 "인증·인가", service-map.md `auth-service`, `.claude/rules/security-config.md`, `contracts/openapi/auth-service.yaml`(tag `keys`)

## 배경
- `/api/v1/internal/**` 는 서비스 간 전용이며 TYPE_SERVICE(`typ=SERVICE` 토큰)만 허용한다. 그런데 서비스 토큰 발급자가 없어서 3단계까지는 사용자 토큰을 그대로 전파했다. 이 방식으로는 내부 호출이 모두 403 이고, 이벤트 소비처럼 사용자 요청이 없는 호출은 아예 할 수 없다.
- 리소스 서버 설정(`jwk-set-uri`)이 가리킬 JWKS 발급자도 없었다.
- 요청 없이 새 프레임워크를 도입하지 않는다(CLAUDE.md). Spring Authorization Server·Keycloak 은 도입하지 않는다. → 2026-11-10 ADR-0054 로 직원 로그인에 Keycloak 도입(라이더는 새 라이더 auth 서비스)(서비스 신원 토큰은 이 ADR 그대로 auth-service).

## 결정
1. **서명**: auth-service 가 RS256 으로 서명한다. 키는 Secret Manager 에서 PKCS#8 PEM 으로 주입한다(`pmart.auth.signing.keys[n].kid/private-key`). 목록의 첫 키로 서명하고, 모든 키의 공개키를 JWKS 로 내보낸다(교체 시 이전 키로 서명한 토큰도 만료까지 검증). 키가 없으면 기동하지 않는다. local/test 에서만 `ephemeral=true`(기동 시 임시 키).
2. **JWKS**: `GET /api/v1/internal/jwks`(RFC 7517, 공개키만, 인증 없음). 모든 서비스와 게이트웨이의 `jwk-set-uri`. auth-service 자신은 같은 키 묶음으로 검증한다(자기 HTTP 호출 없음).
3. **발급**: `POST /api/v1/internal/service-tokens`. 와이어 형식은 OAuth 2.0 client_credentials 그대로(RFC 6749 4.4·5.1·5.2): form `grant_type=client_credentials`, `client_secret_basic`, 오류는 `{"error": "invalid_client"}`. 이렇게 두면 나중에 표준 인가 서버로 바꿔도 호출 측은 그대로다.
   - 클라이언트 = 서비스명. 등록부는 설정(`pmart.auth.service-clients[n].client-id/secret-hash`)이며 **bcrypt 해시만** 둔다. 원문 secret 은 각 서비스에 Secret Manager 로 주입한다.
   - 토큰 claim: `iss`, `sub=src=서비스명`, `typ=SERVICE`, `iat/nbf/exp`, `jti`. 수명은 기본 5분이며 저장 상태가 없다.
   - 미등록 클라이언트와 secret 불일치는 같은 응답·같은 비용으로 처리한다(등록 여부 노출 방지).
   - 표준 프로토콜이라 `Idempotency-Key` 가 없다. common-web 의 `pmart.web.excluded-paths` 로 이 경로만 검사에서 뺀다.
4. **노출 범위**: 두 엔드포인트 모두 `/internal` 아래에 있으므로 게이트웨이가 라우팅하지 않고 `denyAll` 한다. 클러스터 내부(Service DNS)에서만 닿는다.
5. **호출 측**(`libraries/common-security`):
   - `pmart.security.service-token.token-uri` + `client-secret`(PMART_SERVICE_CLIENT_SECRET)이 있으면 `ServiceTokenProvider` 를 등록한다. 만료 30초 전까지 캐시하고, 발급에 실패하면 예외를 던진다(토큰 없이 보내지 않음).
   - RestClient 인터셉터: 경로에 `/internal/` 이 있으면 서비스 토큰, 아니면 사용자 토큰을 전파한다. 이미 지정한 `Authorization` 은 건드리지 않는다.
   - 행위자(사용자)는 토큰이 아니라 **요청 본문**으로 보낸다(예: `ActionAuthorizer` → `POST /internal/authorizations` 의 `actor.principalId`).
   - 기본 `internal-paths` 를 `/internal/**`, `/api/*/internal/**` 로 고친다(계약 servers.url 이 `/api/v1`).

## 대안
- **Spring Authorization Server**: 표준 완비. 새 프레임워크라 요청 없이는 도입하지 않는다. 와이어 형식이 같으므로 나중에 교체하면 호출 측 변경이 없다.
- **Keycloak 등 외부 IdP**: 운영 구성요소가 추가되고, 고객 소셜 로그인 상태(AUTH_*)는 어차피 auth-service 정본이라 이중 관리가 된다.
- **mTLS / 서비스 메시 신원**: 플랫폼 결정이 필요하다(현재 메시 없음). 도입하더라도 이 토큰과 병행할 수 있다.
- **사용자 토큰 전파만 사용**: 이벤트 소비 등 사용자 없는 호출이 불가능하고, `/internal` 보호(TYPE_SERVICE)와 모순된다.

## 결과
- 서비스가 처음 내부 호출을 할 때 `pmart.security.service-token.*` 설정과 Secret 주입, auth-service 등록부 항목(해시)을 추가한다. 로컬은 auth-service `application-local.yml` 에 모든 서비스가 secret `pmart-local` 로 등록돼 있다.
- auth-service 재시작 시 키 교체: 새 키를 목록 뒤에 추가 → 배포 → 맨 앞으로 이동 → 배포 → 토큰 최대 수명(5분)+캐시가 지난 뒤 옛 키 제거.
- 남은 일: 사용자 토큰(소셜 로그인·refresh 회전)은 쿠키/CSRF `[팀 확인 필요]` 결정 후. 클라이언트별 호출 가능 대상(scope) 제한은 첫 내부 호출 구현 시 정한다. 게이트웨이에서 `typ=SERVICE` 토큰 거부(방어 심화)도 검토한다.
