# contracts — 서비스 간 계약의 source of truth

버전 0.1 · 2026-10-23 · 초안. API/이벤트 스키마 변경은 **이 디렉토리를 먼저 수정**한다(CLAUDE.md).

```
contracts/
├── openapi/
│   ├── _common.yaml            공통 헤더·응답·오류·페이지 컴포넌트 (모든 spec이 $ref)
│   └── {service-name}.yaml     서비스별 OpenAPI 3.1
├── events/
│   ├── _envelope.v1.json       이벤트 봉투 (모든 이벤트가 allOf로 포함)
│   ├── _defs.v1.json           공통 타입(Money, Quantity, LineRef, CenterRef …)
│   └── {EventType}.v1.json     이벤트별 JSON Schema (payload 정의)
└── states/
    └── {aggregate}-states.md   상태표 (코드 아님, 서비스가 각자 구현)
```

## 1. 규약

### 1.1 REST
- 경로 `/api/v1/{resources}` 복수형 kebab-case. 명령은 하위 리소스 POST (`POST /api/v1/orders/{orderId}/cancellations`).
- **모든 변경 요청**은 헤더 `Idempotency-Key`(=requestId), `X-Source-System`, `traceparent`를 필수로 받는다. 상태 변경은 `X-Expected-Version` 낙관적 잠금.
- **모든 명령은 짝이 되는 조회를 가진다**: `GET …/{resource}/requests/{requestId}` 또는 결과 ID 조회. 타임아웃은 실패가 아니다.
- 응답 본문은 `CommandResponse`(`status: ACCEPTED|APPLIED|REJECTED|UNKNOWN_RESULT`) 또는 리소스 표현. ACK ≠ 업무 성공.
- 오류는 **RFC 9457 Problem Details**(`application/problem+json`): 표준 `type, title, status, detail, instance` + 확장 `code, traceId, requestId, retryable, details[]`. 스키마는 `_common.yaml#/components/schemas/ErrorResponse`, 공통 코드는 `ErrorCode`.
- 시각은 ISO-8601 UTC(`date-time`). 날짜형 기한은 `date` + `timezone`(기본 Asia/Seoul). 금액은 정수 KRW. 수량은 정수(기준 판매 단위).
- 개인정보 원문·PG 비밀·출입 비밀번호는 응답·로그·이벤트에 넣지 않는다(참조 ID).

### 1.2 이벤트
- 형식은 JSON Schema draft 2020-12(2026-10-23 확정, Avro 아님). Schema Registry는 JSON Schema 모드. 봉투 `_envelope.v1.json`. 각 이벤트 스키마는 `allOf: [envelope, {payload}]`.
- 토픽 `{domain}.{aggregate}.v{n}`, 파티션 키 `aggregateId`. 멱등 키 `(sourceSystem, eventId)`.
- 하위 호환: **필드 추가만** 허용(`additionalProperties: true` 유지). 삭제·타입 변경·rename은 `{EventType}.v2.json` + 새 토픽.
- 이벤트는 확정된 사실만 싣는다. 요청은 명령 API.

### 1.3 버전·변경 절차
1. `contracts/` 수정 → 2. 영향 consumer 목록 작성(events.md 구독자 열) → 3. `./gradlew :services:<name>:check`(계약 검증은 convention plugin이 check에 연결) → 4. 서비스 구현.
- 필드 추가: 같은 버전, `x-added-in` 주석.
- 그 외: 새 버전 파일 추가, 구버전은 `deprecated: true` + 제거 예정일.

## 2. 작성 현황 (2026-10-23)

| 구분 | 수량 | 상태 |
|---|---|---|
| OpenAPI | 51개 서비스(1·2단계 전체 엔드포인트, 3단계 4개는 `x-stage: 3` placeholder) + `_common.yaml` | redocly lint 오류 0. 오류 응답 RFC 9457 |
| 이벤트 JSON Schema | 177개 + `_envelope`/`_defs` | ajv(draft2020, strict) 전부 컴파일 |
| 상태표 | 9개(`states/`) | — |

events.md의 모든 이벤트에 스키마가 있고, service-map.md의 모든 서비스에 spec이 있다(교차 검증 2026-10-23).

## 3. 검증
- OpenAPI: `npx @redocly/cli lint contracts/openapi/*.yaml` (로컬 설치 필요).
- JSON Schema: `npx ajv-cli compile --spec=draft2020 -c ajv-formats -r contracts/events/_envelope.v1.json -r contracts/events/_defs.v1.json -s contracts/events/<Event>.v1.json` (strict 모드이므로 `x-stage` 같은 비표준 키워드는 넣지 않는다. 단계는 description에 적는다).
- 이 초안은 린트를 통과하도록 작성했으나 CI 연결은 별도.
