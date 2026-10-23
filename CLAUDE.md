# CLAUDE.md (Root)

<!-- [ ] 표시는 프로젝트에 맞게 수정. 이 파일은 매 세션 로드되므로 "전체 공통"만 둔다.
     서비스 내부 규칙은 services/{name}/CLAUDE.md 에 둔다. -->

## 시스템 개요
- [시스템 한 줄 설명]
- 전체 비즈니스 흐름: @docs/business/overview.md
- 서비스 책임 경계: @docs/architecture/service-map.md
- 상세 문서(필요 시 읽을 것):
  - `docs/business/flows/` — 서비스 간 유스케이스, saga, 보상 트랜잭션
  - `docs/business/glossary.md` — 공통 용어
  - `docs/architecture/events.md` — 이벤트 카탈로그 (발행자 → 구독자)
  - `docs/adr/` — 아키텍처 결정 기록

## 서비스 목록
| 서비스 | 책임 | 포트(local) | DB |
|---|---|---|---|
| gateway | 라우팅, 인증 토큰 검증 | 8080 | - |
| member | 회원, 인증 | 8081 | member_db |
| order | 주문 생성/상태 관리 | 8082 | order_db |
| payment | 결제, 환불 | 8083 | payment_db |
| inventory | 재고 | 8084 | inventory_db |
<!-- [ ] 실제 서비스로 교체 -->

## Tech Stack (공통)
- Java 21, Spring Boot 3.x, Gradle multi-module (Kotlin DSL)
- Spring Cloud Gateway, OpenFeign, Resilience4j
- Messaging: [Kafka], Outbox 패턴
- DB: 서비스별 독립 [PostgreSQL], Flyway
- Observability: Micrometer Tracing + [OpenTelemetry], 구조화 JSON 로그
- Local infra: Docker Compose

## Repo 구조
```
/
├── services/{name}/     # 각 마이크로서비스 (독립 배포 단위)
├── libs/                # 공통 라이브러리 (common-error, common-event 등)
├── contracts/           # OpenAPI, 이벤트 스키마 — 서비스 간 계약의 source of truth
├── infra/               # docker-compose, k8s manifest
└── docs/
```

## Commands
```bash
docker compose -f infra/docker-compose.yml up -d     # Kafka, DB 등
./gradlew :services:order:bootRun                    # 단일 서비스 실행
./gradlew :services:order:test                       # 단일 서비스 테스트
./gradlew build                                      # 전체 빌드
./gradlew contractTest                               # 계약 테스트
```
- 작업 중인 서비스만 빌드/테스트한다. 전체 빌드는 libs/ 또는 contracts/ 변경 시에만.

## MSA 핵심 규칙
**서비스 경계**
- 다른 서비스의 DB에 직접 접근하지 않는다. 동기 조회는 API, 상태 전파는 이벤트로만.
- 서비스 간 domain 클래스를 공유하지 않는다. `libs/`에는 기술 공통 코드만 둔다 (비즈니스 로직 금지).
- 로직이 어느 서비스 책임인지 불명확하면 `service-map.md`를 확인하고, 그래도 모호하면 질문한다.

**통신**
- 동기 호출(Feign)에는 timeout, retry, circuit breaker를 반드시 설정한다.
- 동기 호출 체인은 최대 2 hop. 그 이상이면 이벤트 기반으로 설계한다.
- 이벤트 발행은 Outbox 패턴으로 (DB 트랜잭션과 발행의 원자성 보장).
- 이벤트 consumer는 멱등하게 구현한다 (eventId 기반 중복 처리).

**분산 트랜잭션**
- 2PC 사용 금지. Saga(choreography 기본)와 보상 트랜잭션으로 처리한다.
- 새 saga를 만들거나 수정하면 `docs/business/flows/`를 같이 갱신한다.

**계약 변경**
- API/이벤트 스키마 변경은 `contracts/`를 먼저 수정한다.
- 하위 호환 유지: 필드 추가만 허용, 삭제/타입 변경/rename은 새 버전으로 (`/v2`, `OrderCreatedV2`).
- 계약을 바꾸면 영향받는 모든 consumer 서비스를 찾아서 목록으로 보고한다.

**공통 규약**
- 에러 응답: `{ "code": "ORDER_NOT_FOUND", "message": "...", "traceId": "..." }`
- 모든 요청/이벤트에 traceId 전파. 로그에 traceId 포함
- API 경로: `/api/v1/{resources}` 복수형 kebab-case
- 시간은 UTC 저장, `Instant` 사용

## 하지 말 것
- prod 설정, secret, `.env`, k8s prod manifest 수정 금지
- 이미 배포된 Flyway migration, 이벤트 스키마 버전 수정 금지
- `libs/` 변경은 전 서비스에 영향 → 수정 전 먼저 제안할 것
- 테스트 삭제/`@Disabled`로 통과시키지 않기
- 비즈니스 규칙을 추측해서 구현하지 않기. 문서에 없으면 질문

## 작업 완료 기준
1. 변경한 서비스의 빌드/테스트 통과
2. 계약 변경 시 `contractTest` 통과 + 영향 서비스 목록 보고
3. 비즈니스 규칙/흐름이 바뀌었다면 관련 docs를 같은 커밋에서 갱신
