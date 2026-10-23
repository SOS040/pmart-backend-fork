# contracts 작성 규칙 (초안 작성자용)

## YAML 작성 시 반드시 지킬 것 (린트 실패 원인 상위 4개)
1. flow mapping `{ ... }` 안에는 **쉼표·콜론·중괄호·따옴표가 들어간 문장을 넣지 않는다.** 설명이 필요하면 block style로 푼다.
   - 나쁨: `qty: { type: integer, description: 10,000g 초과 시 2 }`
   - 좋음: `qty:` / `  type: integer` / `  description: 총 상품중량 10000g 초과 시 2`
2. `summary`/`description` 값에 `: `(콜론+공백)이 있으면 **큰따옴표로 감싼다.** `[`로 시작해도 감싼다.
3. OpenAPI **3.1**이다. `nullable: true` 금지 → `type: [string, 'null']`.
4. 모든 spec은 `servers`, `security`, `tags`를 가진다. 공통 컴포넌트는 `_common.yaml#/components/...`로 `$ref`.

## 구조 템플릿
- `info.description`에 정본/비정본 한 줄, `docs/architecture/service-map.md` 절 번호.
- 경로: 고객/현장 단말용 `/…`, 서비스 간은 `/internal/…`.
- 변경 요청: `IdempotencyKey`, `SourceSystem` 파라미터 $ref 필수. 상태 변경은 `ExpectedVersion`.
- 명령마다 짝 조회 엔드포인트(`GET …/{requestId}` 또는 결과 ID 조회).
- 응답: 202/200 `_common.yaml#/components/responses/Accepted`, 4xx/5xx는 공통 responses.
- 오류 본문은 반드시 `application/problem+json` + `ErrorResponse`(RFC 9457). `application/json`으로 오류를 내보내지 않는다.
- 서비스별 오류 코드는 해당 응답 `description`에 나열 (예 `SKU_NOT_SALEABLE`).

## 이벤트 JSON Schema
- `allOf: [ {"$ref": "_envelope.v1.json"}, { eventType const, aggregateType const, payload } ]`
- 공통 타입은 `_defs.v1.json#/$defs/...` 참조. payload `additionalProperties: true`.
- `description`에 발행자·구독자·토픽·단계(①②③) 명시 (docs/architecture/events.md와 일치).
- ajv strict 모드를 쓰므로 `x-stage` 등 비표준 키워드 금지. (OpenAPI의 `x-stage`는 허용.)
- 여러 서비스가 같은 이벤트를 발행하면 파일은 하나, payload에 `centerType`/`restrictionType` 같은 구분 필드.

## 검증 (작성 후 반드시 실행)
```
node <tools>/validate.js <repoRoot>
<tools>/node_modules/.bin/redocly lint contracts/openapi/<file>.yaml --config contracts/openapi/redocly.yaml
<tools>/node_modules/.bin/ajv compile --spec=draft2020 -c ajv-formats -r contracts/events/_envelope.v1.json -r contracts/events/_defs.v1.json -s contracts/events/<Event>.v1.json
```
