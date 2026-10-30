# auth-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `auth-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/auth-service.yaml` → `openApiGenerate` 가 `com.pmart.auth.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.auth.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:auth-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:auth-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8501.

## 서비스 신원 (ADR-0011)
- `GET /api/v1/internal/jwks`(공개키), `POST /api/v1/internal/service-tokens`(client_credentials, Basic). 둘 다 permit-path, 게이트웨이 비노출.
- 서명키 `pmart.auth.signing.keys` (Secret Manager, PKCS#8 PEM, 첫 키가 활성). 없으면 기동 실패. local/test 만 `ephemeral=true`.
- 클라이언트 등록부 `pmart.auth.service-clients` 는 bcrypt 해시만. 토큰·secret 로그 금지.
- 자기 토큰 검증은 `KeyConfig` 의 JwtDecoder(같은 키 묶음) — `jwk-set-uri` 없음.

## 운영자 로그인 (ADR-0018)
- `POST /api/v1/auth/operator-logins`(permit-path, Idempotency-Key 제외 경로) → access(`typ=OPERATOR, roles, centers, sid`) + refresh. 비밀번호·토큰 로그 금지.
- refresh token 은 SHA-256 만 저장, 한 번만 쓴다. 재사용이면 세션 폐기(`TOKEN_REUSED`). 같은 Idempotency-Key 재전송도 재생하지 않는다(토큰을 저장하지 않으려고).
- 계정 관리 `/api/v1/operator-accounts/**` 는 `pmart.auth.operator.admin-role`(기본 `OPERATOR_ADMIN`)만. 수치(토큰 수명·잠금)는 `pmart.auth.operator.*`.
- 첫 관리자: `pmart.auth.operator.bootstrap.login-id`·`password-hash`(bcrypt, Secret Manager). 로컬 프로파일은 `admin` / `pmart-local`.

## 상태
서비스 신원 토큰·JWKS(2026-10-23), 운영자 로그인·계정 관리·토큰 갱신·로그아웃·현재 세션(2026-10-30). 소셜 로그인(D6)·행위 검증·grant 는 501 UNSUPPORTED_ACTION.
