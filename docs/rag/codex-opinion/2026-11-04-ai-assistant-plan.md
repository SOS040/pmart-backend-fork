# ai-assistant-service 1차 — 내부 운영자용 정책·업무 절차 RAG (검토 10개 + 2차 7개 + 3차 6개 반영판)

> 3차 검토 반영 요약(아래 본문에 녹임 — 충돌 시 이 요약이 우선)
> 1. **현재 권한 재검사는 저장 답변을 돌려주는 모든 경로**에 건다: `GET /{answerId}`, `GET ?requestId`, POST 멱등 재응답(COMPLETED 재생), lease 상실 뒤 재조회 응답. 대체 제안 문서를 ID 로 추가 조회할 때도 같은 `access_scope` 필터.
> 2. **출처**: 토큰에 `src` 가 있으면 헤더와 일치해야 한다. 없으면 **1차는 `backoffice-web` 하나로 고정**한다(설정 목록 아님 — 호출자가 고를 수 없음). 다른 값은 403.
> 3. **스냅숏 고정·삭제 원자성**: 활성 스냅숏 선택과 PROCESSING 행의 `snapshot_id` 기록을 **한 트랜잭션** 안에서, 스냅숏 전환·삭제와 같은 advisory lock(공유/배타)으로 직렬화한다. 완료 답변의 인용(citations jsonb)에 **청크별 `access_scope` 를 함께 저장**해 스냅숏이 지워진 뒤에도 권한을 재검사한다.
> 4. **발췌 길이 제한은 온전한 청크 개수를 줄여서** 맞춘다. 청크 앞부분을 자르는 규칙("… 원문 계속")은 없앤다. 너무 큰 절은 색인 단계에서 조건·예외·표·목록 블록을 깨지 않는 경계에서만 나누고, 나눌 수 없으면 통째로 둔다.
> 5. **이력 질문**은 `SUPERSEDED` 만 포함한다. `EXCLUDED_FROM_PHASE1` 은 이력 질문에서도 계속 제외.
> 6. 스키마·상태:
>    - `doc_chunk` 에 `proposed_superseded_by text[]`, `precedence_rank int` 추가.
>    - 해시 이름은 `request_hash` 로 통일.
>    - 실패 기록에 `last_error_code` 로 `CONFIG_REQUIRED`(→ 422)·`DEPENDENCY_UNAVAILABLE`(→ 503)를 구분.
>    - lease 가 **만료된 PROCESSING** 은 GET 에서 202 가 아니라 409 `INVALID_STATE`(retryable, "같은 Idempotency-Key 로 POST 재시도") — 조회만 하는 클라이언트가 무한 대기하지 않는다.

> 2차 검토 반영 요약
> 1. 재조회 응답 모양과 X-Source-System 검증(토큰 `src` 대조, 없으면 허용 목록)
> 2. 멱등 해시 = 정규화 질문 + 허용 context
> 3. lease 를 잃은 작업자: 완료·실패 모두 조건부, 0건이면 재조회 응답, 하드 타임아웃 < lease
> 4. n-gram 은 보조 휴리스틱이고 생성 충실도는 미검증. 발췌는 청크 단위
> 5. 우선순위·대체를 검색·응답에 적용하는 규칙
> 6. 스냅숏 수명·한 번 읽은 바이트로 해시와 청크·색인 중 변경 감지
> 7. 상태 이름·`question` 열 정리. 사본에서 잘렸던 503·422·차원 검증은 원본에 있음

## Context
- 사용자 요청(2026-11-04): 3단계로 미뤄 둔 RAG 중 1차 범위를 앞당겨 구현한다. 본사·센터 운영자가 정책·업무 절차를 물으면, 현재 적용되는 저장소 문서를 검색해 근거와 함께 답한다. 범위는 문서 질의응답과 근거 표시까지다.
- 하지 않는 것:
  - 실시간 조회와 명령
  - 클라이언트 수정
  - LAN 배포
  - 실제 문서의 외부 전송
- 추적 작업 A·B·C 는 미뤄 둔다(메모리 기록).
- 1차 계획 승인 뒤 사용자가 검토 10개를 줬다. 그중 4·5·6번(트랜잭션 경계, 인용 실패 처리, FORBIDDEN_SCOPE 추론)은 1차 계획의 결함이었다. 이 판은 그것을 고친 것이다.
- **이미 바뀐 파일**: `contracts/openapi/ai-assistant-service.yaml` 은 1차 계획대로 1.0 으로 고쳐 둔 상태(미커밋)다. 이 판에 맞춰 다시 고친다.

## 확인된 사실 (요약)
- **버전**:
  - Boot 4.0.9 / Cloud 2025.1.4 / Java 21.
  - Spring AI 2.0.x 는 Boot 4.0·4.1 대상 GA 다.
    - https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
    - https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0
  - 최신 GA 는 **2.0.2**(2026-10-22 게시)이고 2.1 은 RC 다.
    - https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml
  - `spring-ai-model`·`spring-ai-client-chat`·`spring-ai-commons` 2.0.2 pom 이 있는 것을 확인했다.
  - **실제 Gradle 해석·컴파일 결과는 구현 첫 단계에서 확인해 보고에 붙인다.**
- **템플릿**: approval-service(JdbcTemplate, 운영자 검사, flow IT), case-service(골격). 컨트롤러는 생성된 `*Api` 를 구현한다. `ContractStubTest`·`ServiceBootIT` 패턴이 있다.
- **인증**: `PmartPrincipal`(type OPERATOR/CUSTOMER/RIDER…, roles, centerScope), 운영자 검사는 코드에서 한다. 게이트웨이가 서비스로 직접 라우팅한다.
- **공통 `RequestIdempotency.execute` 는 액션 전체를 한 트랜잭션으로 감싼다** → 모델 호출 경로에 쓰지 않는다(아래 4).
- **DB**: 서비스별 DB `ai_assistant_service`. 공용 Postgres 는 `postgres:17-alpine` 이고 pgvector 는 없다.
- **문서 지위**:
  - 업무 문서 대부분이 "설계안" → 절·문단 태그로 지위를 정한다.
  - ADR 상태(L3): 승인 10 / 제안 22 / 초안 1. **ADR-0019 자신도 "제안"이고, ADR-0025 는 "제안, 사용자 검토 전"이다.**
  - 우선순위 규칙은 `domain-map.md:35`, 충돌 해결표는 `:37-50`.

## 설계

### A. 계약 (`contracts/openapi/ai-assistant-service.yaml` 다시 고침)
- `POST /assistant/policy-questions` (Idempotency-Key, X-Source-System, `{question, context?}` — context 는 힌트일 뿐 권한 근거가 아니다)
  - 200 `PolicyAnswer`: 완료(ANSWERED·NO_GROUNDING·REALTIME_DATA_REQUIRED·OUT_OF_SCOPE).
  - 202 `PolicyAnswer{state: PROCESSING}` + `Retry-After`: 같은 키가 처리 중(동시 요청).
  - 409 `IDEMPOTENCY_CONFLICT`: 같은 키에 다른 질문.
  - 503 `DEPENDENCY_UNAVAILABLE`: 임베딩·검색·모델 장애 — 저장된 완료 결과 없음, 같은 키로 재시도.
  - 422 `CONFIG_REQUIRED`: 활성 색인 없음.
  - 400, 401/403.
- `GET /assistant/policy-questions?requestId=…`(operationId `findPolicyAnswerByRequestId`, 헤더 `X-Source-System` 필수): 최초 응답 유실용이다.
  - **식별 범위는 (sourceSystem, 호출자 subject, requestId)** 다.
  - 응답:
    - 200: 완료 — ANSWERED·NO_GROUNDING·REALTIME_DATA_REQUIRED·OUT_OF_SCOPE 본문
    - 202: `state=PROCESSING` + `Retry-After`(lease 유효할 때만. 만료됐으면 409 재시도 안내)
    - 409 `INVALID_STATE` + `retryable=true`: 직전 시도 실패(FAILED_RETRYABLE) — 같은 Idempotency-Key 로 POST 를 다시 보내라
    - 404: 그 범위에 행이 없음(처리 전 실패·다른 출처·다른 운영자 포함, 존재를 드러내지 않음)
- **X-Source-System 검증**(POST·GET 공통): 헤더를 그대로 믿지 않는다.
  - 토큰에 `src` 클레임(`PmartPrincipal.sourceSystem`)이 있으면 헤더와 같아야 한다.
  - 없으면(지금 운영자 토큰은 `src` 가 없다 — auth-service 확인) **1차는 `backoffice-web` 하나로 고정**(설정 목록이 아니라 상수 — 호출자가 다른 출처를 고를 수 없음).
  - 아니면 403.
- **멱등 해시 규칙(계약에 명시)**: `sha256(정규화 질문 + "\n" + 정규화 context)`.
  - 질문: NFC, 앞뒤 공백 제거, 연속 공백 하나로.
  - context: 허용 키(`screen`·`centerId`)만, 값은 같은 정규화, 키 정렬 JSON, 없으면 `{}`.
  - 같은 키에 해시가 다르면 409 `IDEMPOTENCY_CONFLICT`.
- `GET /assistant/policy-questions/{answerId}`: 본인 + **현재 권한 재검사**(지금도 OPERATOR 인지, 그 답변 근거 문서의 접근 범위를 지금도 갖는지). 아니면 404.
- `PolicyAnswer`:
  - `answerId, requestId, state, answer, citations[], indexSnapshotId, askedAt`
  - **`answerMode`**(BASELINE_EXTRACTIVE | GENERATIVE | NONE) · **`retrievalMode`**(BASELINE_HASH | SEMANTIC | NONE) · `model{chat, embedding}`
- `Citation`(서버 메타데이터로만 만든다):
  - `chunkId, documentId, path, section, excerpt, asOf{snapshotId, sourceRevision, sourceDirty, indexedAt}`
  - `documentVersion?, effectiveDate?`(원문에 있을 때만)
  - **`authority`**(CONFIRMED · ADOPTED_DESIGN · PROPOSAL · ASSUMPTION · UNDECIDED · EXTERNAL_REFERENCE)
  - **`lifecycleStatus`**(ACTIVE · SUPERSEDED · SUPERSESSION_PROPOSED · EXCLUDED_FROM_PHASE1) + `supersededBy?`(활성 대체의 근거 문서 ID) + `proposedSupersededBy[]`(대체를 제안한 문서 ID — SUPERSESSION_PROPOSED 일 때)
  - **`implementationEvidence?{recordDate, note}`**: 날짜가 붙은 구현 기록 절일 때만. 답변에 "○○ 기준 기록"으로 밝힌다.
- `FORBIDDEN_SCOPE`: 계약에 남기되, **명시적 접근 요청이 정책상 거부된 경우**로 한정한다. 1차 문서는 모두 운영자 공용이라 정상 경로에서는 나오지 않는다.
- 상태별 예제를 모두 넣는다. `answerMode=BASELINE_EXTRACTIVE` 예제에는 "기준선 — 의미 검색·생성 품질 미검증" 설명을 붙인다.

### B. 서비스 골격·빌드
- `settings.gradle.kts` 에 등록한다.
- `services/ai-assistant-service/build.gradle.kts`: `pmart.spring-service`, `basePackage com.pmart.assistant`, common-web/observability/security/messaging, service-data, service-observability.
- 카탈로그: `spring-ai = "2.0.2"`, `spring-ai-bom`(platform), `spring-ai-model`, `spring-ai-client-chat` — **이 서비스만**. 제공자 모듈은 넣지 않는다. Boot/Cloud 는 바꾸지 않는다.
- 설정(ADR-0029):
  - bootstrap `application.yml`
  - 설정 저장소 `ai-assistant-service.yml`(port 8508, `pmart.messaging.domain: platform`, `pmart.assistant.*`) / `-local.yml` / `-lan.yml`(DB, `{cipher}` 자리만)
  - 시험 사본 + `-it-env.yml`
  - 게이트웨이 라우트 `/api/v1/assistant/**`(+ 게이트웨이 시험 사본)
  - 설정 저장소 push 는 배포 때까지 보류한다.
- 별도 스레드를 쓰면 `ContextExecutorService.wrap`(작업 A 교훈).

### C. 저장소 (`V1__init.sql`, 서비스 소유)
- `CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS pg_trgm;`(둘 다 실제로 쓴다)
- `index_snapshot`
  - 열: `snapshot_id, embedding_model, embedding_model_version, embedding_dim, preprocessing_hash, manifest_hash, source_revision, source_dirty, state(BUILDING|ACTIVE|RETIRED|FAILED), chunk_count, created_at, activated_at`
  - ACTIVE 는 하나만(부분 유니크).
- `index_source_file(snapshot_id, path, sha256, bytes)`: 실제로 읽은 파일 목록. 작업 트리가 더러우면 HEAD 로 재현되지 않는 입력을 식별한다.
- `doc_chunk`
  - 열: `snapshot_id, chunk_id, document_id, path, section_path, domain_area, authority, lifecycle_status, superseded_by, proposed_superseded_by text[], precedence_rank int, impl_record_date, impl_note, document_version, effective_date, stage, access_scope, content, content_hash, embedding vector`
  - PK `(snapshot_id, chunk_id)`. `pg_trgm` GIN 인덱스를 둔다.
- `embedding_cache`
  - 키: **(model, model_version, dim, preprocessing_hash, content_hash)**
  - 모델이나 전처리가 바뀌면 캐시가 섞이지 않는다.
- `policy_answer`(공통 `request_record` 를 쓰지 않는다):
  - 열: `answer_id, source_system, asker_subject, request_id, request_hash, state(PROCESSING|COMPLETED|FAILED_RETRYABLE), lease_until, attempt, last_error_code, result_state, answer, citations jsonb, snapshot_id, answer_mode, retrieval_mode, chat_model, embedding_model, question(널 허용 — 원문 저장 설정일 때만), created_at, completed_at`
  - 유니크 `(source_system, asker_subject, request_id)`.
  - **질문 원문은 기본으로 저장하지 않는다**(`request_hash` 만). `pmart.assistant.answers.store-question=true` 일 때만 `question` 에 쓴다(기본 false).
- 보존: `pmart.assistant.answers.retention`(기본 `[결정 필요]` — 설정이 없으면 삭제하지 않음). 설정되면 `@Scheduled` 정리 작업이 오래된 `COMPLETED`·`FAILED_RETRYABLE` 행을 지운다(PROCESSING 은 지우지 않음). 응답·인용·원문이 모두 이 표에만 있어서(공통 `request_record` 미사용) 보존 정책이 하나로 모인다.
- **스냅숏 수명**: 1차는 RETIRED·FAILED 스냅숏을 자동으로 지우지 않는다. 정리는 수동 작업 `pruneSnapshots` 로만 하고, 유예 시간(lease×2 이상) 경과 + 그 snapshot_id 를 참조하는 PROCESSING 행이 없을 때만 지운다. 처리 중 요청이 고정한 스냅숏은 처리 도중 사라지지 않는다.

### D. 질의 처리 흐름 (트랜잭션 경계 — 검토 4)
1. **검증**: 운영자·출처 검사 → 질문·context 정규화 → `request_hash`.
2. **선점(짧은 TX)**: `(src, subject, requestId)` 로 INSERT `PROCESSING`(lease 2분, attempt=1) → 커밋.
   - 이미 있으면:
     - COMPLETED + 같은 hash → 저장된 결과(200)
     - 다른 hash → 409
     - PROCESSING 이고 lease 유효 → 202
     - PROCESSING 인데 lease 만료 또는 FAILED_RETRYABLE → 조건부 UPDATE 로 다시 선점(attempt+1)한다(한 요청만 성공).
3. **질문 분류**(모델 없음, E) → REALTIME_DATA_REQUIRED·OUT_OF_SCOPE 면 바로 6.
4. **snapshot 고정(한 TX, 스냅숏 advisory lock 공유)**: 활성 snapshotId 를 읽고 같은 TX 에서 PROCESSING 행에 기록한다 → 이후 검색·인용·저장에 같은 값. 없으면 `FAILED_RETRYABLE` + `last_error_code=CONFIG_REQUIRED` 로 두고 422.
5. **트랜잭션 밖에서**: 임베딩 → 검색(허용 범위 WHERE) → 답변 생성 → 인용 검증(F).
   - **하드 타임아웃** `pmart.assistant.processing.timeout`(기본 60초)이 lease(`pmart.assistant.processing.lease`, 기본 120초)보다 반드시 짧다. 기동 때 `timeout < lease` 를 검사하고 아니면 기동 실패.
   - 정상 처리는 lease 안에 끝나므로 heartbeat 는 두지 않는다.
   - 시간 초과·장애 → 6-실패.
6. **완료·실패 기록(짧은 TX, 둘 다 선점 조건부)**:
   - 완료: `UPDATE … SET state='COMPLETED', … WHERE answer_id=? AND attempt=? AND state='PROCESSING'`.
   - 실패: `UPDATE … SET state='FAILED_RETRYABLE', last_error_code=?(CONFIG_REQUIRED|DEPENDENCY_UNAVAILABLE), lease_until=NULL WHERE answer_id=? AND attempt=? AND state='PROCESSING'`.
   - **갱신 0건(다른 작업자가 lease 를 가져감)이면 내가 만든 답·오류를 돌려주지 않는다.** 행을 다시 읽어 현재 상태대로 응답한다(COMPLETED → 현재 권한 재검사 후 200, 유효 lease PROCESSING → 202, 만료 lease PROCESSING → 409 재시도 안내, FAILED_RETRYABLE → last_error_code 대로 422/503).
   - 내 갱신이 성공했을 때만 200(완료)·503(실패)이다.
- **시험**:
  - 같은 키 동시 2요청(하나는 202)
  - 처리 중 중단 뒤 lease 만료 재선점
  - **lease 를 잃은 작업자의 완료·실패가 덮어쓰지 못함**(attempt 불일치 → 저장 상태 응답)
  - 503 뒤 같은 키 재시도 성공
  - 최초 응답 유실 → requestId 조회로 회수
  - 다른 sourceSystem·다른 운영자는 404
  - 토큰 `src` 와 다른 X-Source-System 은 403
  - 같은 질문 + 다른 context → 409

### E. 질문 분류 (의도 기반 — 검토 7)
- 규칙: **대상 식별자(ORD-/PA-/CLM-/SKU·주문번호 숫자 등) + 실행 의도(명령형: "~해줘/~해 주세요/처리해/승인해/환불해/취소해")** → OUT_OF_SCOPE.
- **대상 식별자 + 현재 상태 의도("지금/현재 상태/어디까지/됐어?/몇 개 남았")** → REALTIME_DATA_REQUIRED.
- 나머지(절차·정책·기준·"어떻게/언제/왜/무엇")는 정책 질문이다.
- 식별자 없는 명령형("재고 줄여줘")은 OUT_OF_SCOPE 이지만, "취소 처리 절차를 알려줘"는 정책 질문이다.
- 시험(긍정·부정):
  - "취소 처리 절차를 알려줘" → 정책
  - "ORD-123을 취소해줘" → 실행 요청
  - "재고 몇 개 이하에서 보충하는 정책인가?" → 정책
  - "ORD-123 지금 어디까지 왔어?" → 실시간
  - "환불 승인은 누가 하나요?" → 정책
  - "CLM-9 환불해" → 실행 요청
  - 등 20개 이상.

### F. 검색·답변·인용 검증 (검토 3·5·6)
- **검색**: 고정된 snapshot + `access_scope ∈ 호출자 허용 범위` 를 **SQL WHERE 로 먼저** 건다. 권한 밖 문서는 검색하지도, 모델에 넣지도 않는다. 허용 범위에 근거가 없으면 NO_GROUNDING. 점수는 코사인 + `pg_trgm` 유사도 혼합이고 임계값을 둔다.
- **우선순위·대체 적용 규칙(2차 검토 5)**:
  - **현재 정책 질문**(기본): `lifecycle_status IN ('SUPERSEDED','EXCLUDED_FROM_PHASE1')` 청크를 검색 WHERE 에서 뺀다.
  - **이력·과거 질문**("예전에는/이전/과거/변경 전/왜 바뀌었" 의도)일 때만 **SUPERSEDED 를** 포함하고 [대체됨]을 표시한다. `EXCLUDED_FROM_PHASE1` 은 이력 질문에서도 제외한다.
  - **순위**: 임계값을 넘은 후보끼리만 `최종 점수 = 유사도 + 지위 가중(확정 > 채택 설계 > 설계안 > 가정 > 미결정·외부 참고) + 우선순위 등급 가중(domain-map:35)` 으로 정렬한다. 가중치는 설정으로 둔다. 임계값 미만 문서를 지위로 끌어올리지 않는다.
  - **제안 문서만 찾은 경우**(확정·채택 근거 0): 답변 맨 앞에 "확정 정책 근거 없음 — 아래는 설계안·미결정 기준"을 붙인다(상태는 ANSWERED 유지, 인용 지위로 구분).
  - **SUPERSESSION_PROPOSED** 근거를 인용할 때 그 `proposedSupersededBy` 문서도 함께 찾아(같은 스냅숏, ID 로) 인용에 붙이고 "대체 제안 있음(미승인)"으로 표시한다.
  - **충돌**: 같은 주제의 확정 근거와 다른 문서의 다른 값이 함께 나오면 합치지 않는다. 각각 지위·우선순위 등급과 함께 나열하고, 저장소 우선순위 규칙에 따른 우선 근거를 표시한다(임의로 하나로 결론 내지 않음).
- **답변**:
  - GENERATIVE: ChatClient — 근거는 `<doc id authority lifecycle impl_date>` 로 감싸고, 근거 안 지시문은 데이터로 취급하라고 지시한다. 출력은 `{answer, usedIds, grounded}` 구조다.
  - BASELINE_EXTRACTIVE: 서버가 상위 근거를 **온전한 청크 단위**로 보여 준다(조건·예외·표 헤더가 함께 남음). 길이 제한은 청크 개수를 줄여서 맞추고 청크를 자르지 않는다. 지위·기준일 표시를 붙인다. 생성 없음.
- **인용 검증**:
  - ① `usedIds ⊆ 검색 결과`(ID 유효성 — 확실한 검사)
  - ② **보조 휴리스틱**(뒷받침 근사이지 검증이 아님): 답변 문장별 근거 n-gram 겹침 + **부정어 불일치**(근거 "필요하다" ↔ 답 "필요하지 않다" 류) + **숫자·기간 불일치**(답에 근거에 없는 숫자) 검사
  - ①이 실패하거나 ②가 위반을 잡으면 **원래 답변을 버리고** 1회 제한 재생성(검증 실패 사실을 지시에 넣음)을 한다. 그래도 실패하면 **검증된 발췌 응답**(BASELINE_EXTRACTIVE)으로 바꾼다. 발췌할 근거도 없으면 NO_GROUNDING.
  - 잘못된 인용만 빼고 원문을 유지하는 경로는 없다.
  - ②를 통과했다고 "근거로 검증됨"이라 하지 않는다. **생성 답변의 충실도는 실제 모델 평가 전까지 미검증**으로 문서·보고에 남긴다.
- **지위 표시**:
  - 답변은 근거마다 [확정]·[설계안]·[미결정]·[대체 제안] 표시를 붙인다. 충돌하는 근거는 합치지 않고 나란히 둔다.
  - 구현 여부는 implementationEvidence 의 기준일로만 말한다("2026-10-31 기준 기록: …, 현재 상태는 이 기록만으로 확인할 수 없음").
  - 근거가 없으면 "확인할 수 없음"(NO_GROUNDING).
- **모델 제공자**(결정 전):
  - `pmart.assistant.model.provider` = `local`(기본) | `fake`(시험).
  - `local`: 임베딩은 `LocalHashEmbeddingModel`(문자 n-gram 해시, 이름·버전·전처리 해시를 갖는 Spring AI `EmbeddingModel`), 답변은 BASELINE_EXTRACTIVE → `answerMode`/`retrievalMode` 가 기준선을 드러낸다.
  - 제공자 모듈은 결정 뒤 추가한다.

### G. 지식 원천과 색인 (검토 1·2·8)
- **명시 목록** `services/ai-assistant-service/src/main/resources/knowledge/manifest.yml`:
  - 문서별 경로, 업무 영역, 기본 authority, 우선순위 등급(`domain-map.md:35` 그대로), 적용 단계, 접근 범위, 지위 예외(`overrides`).
  - 넣는다: overview, domain-map, glossary, flows 4개, 업무 명세 8개, service-map, ADR 전부, contracts/states 8개.
  - 넣지 않는다: worklog, handoff, 다른 architecture, 코드·시험·배포·비밀.
- **청크 분할**(`MarkdownChunker`): 제목 계층, 표 통째(헤더 유지), 조건 문장과 목록을 함께, 규칙 ID 헤딩 단위 유지. 큰 절은 표·목록·조건 블록을 깨지 않는 문단 경계에서만 나누고, 그런 경계가 없으면 통째로 둔다. `chunkId = sha256(path + section + 정규화 본문)`.
- **authority**: 절·문단 태그가 문서 기본값을 덮는다. 문단 시작 태그가 있으면 그 문단을 따로 청크로 만든다. 없는 버전·적용일은 만들지 않는다.
- **lifecycleStatus — 대체 활성화 규칙(검토 1)**:
  - "대체:" 선언을 파싱해 관계 후보를 만든다.
  - 관계는 **대체하는 문서가 승인(authority CONFIRMED 인 ADR 상태 "승인")이고 대상 절·결정 번호가 명시된 경우에만 ACTIVE** 다. 이때 대상 절이 SUPERSEDED 가 된다(문서 전체가 아니라 명시된 결정·절만).
  - 그 밖(제안·초안 ADR, 대상이 모호함)은 대상 쪽을 **SUPERSESSION_PROPOSED** 로 표시만 하고 원래 지위를 유지한다.
  - 예:
    - 0026(승인)→0011 결정 3·5: 활성
    - 0025(제안·검토 전)→0019 결정 1·8: 제안으로만 표시, 0019 지위 유지(0019 도 제안)
    - 0015(제안)→Commerce L1072 카테고리 확정 정책: 제안 표시만 하고 CONFIRMED 유지 + 제안 ADR 이 있다는 표시
  - 규칙에 안 맞는 예외는 manifest `overrides` 로만, 근거를 적어서 처리한다.
- **implementationEvidence(검토 2)**: "구현 상태 (YYYY-MM-DD…)"·"구현 (YYYY-MM-DD…)" 절은 authority 와 별도로 recordDate 를 기록한다. 지위 축은 바꾸지 않는다.
- **색인 실행**(통제된 작업, 기동 때 하지 않음):
  - Gradle `:services:ai-assistant-service:ingestKnowledge -Prepo=<경로>` → 앱을 `pmart.assistant.ingest.run=true` 로 띄워 한 번 적재하고 종료한다.
  - 절차:
    1. **advisory lock**(`pg_try_advisory_lock`)으로 동시 색인을 막는다. 잡지 못하면 즉시 실패한다.
    2. 새 snapshot 을 BUILDING 으로 만든다.
    3. **파일마다 바이트를 한 번만 읽고**, 같은 바이트로 sha256 과 청크를 함께 만든다(`index_source_file` 기록).
    4. 임베딩한다(캐시 키 C).
    5. **차원을 검증한다**(모델 선언 차원 = 실제 벡터 길이, 아니면 FAILED).
    6. **색인 중 변경 감지**: 끝날 때 manifest 의 모든 파일을 다시 해시해 3의 값과 비교한다. 하나라도 다르면(색인 도중 수정·삭제) FAILED — 일관되지 않은 입력으로 활성화하지 않는다.
    7. 검증한다(청크 > 0, manifest 경로 모두 존재).
    8. 한 TX 에서 ACTIVE 로 전환하고 이전 것은 RETIRED(삭제하지 않음, C 의 스냅숏 수명).
  - 실패하면 FAILED 이고 기존 ACTIVE 는 그대로다.
  - `source_revision`=`git rev-parse HEAD`, `source_dirty`=`git status --porcelain` 이 비어 있지 않음.
- **로컬 실행(검토 8)**:
  - `deploy/local/ai-assistant/docker-compose.yml`: 선택 실행용 `pgvector/pgvector:pg17`, 포트 15433, DB `ai_assistant_service`.
  - 공용 Postgres·`init-db.sql` 은 바꾸지 않는다.
  - `ai-assistant-service-local.yml` 이 이 DB 를 가리킨다.
  - 시험은 이 서비스 시험 지원 클래스에서 같은 이미지를 쓴다(common-test 공용 이미지는 바꾸지 않는다).

### H. 로그·데이터 경계
- 질문·문서 본문·토큰은 로그와 span 속성에 남기지 않는다(답변 ID·상태·소요 시간·청크 수만).
- 다른 서비스 DB 에는 접근하지 않는다.
- 모델 키 자리는 lan `{cipher}`, local `${PMART_ASSISTANT_*:}` 로 둔다(지금은 쓰지 않음).

### I. 문서
- ADR-0036: 1차 RAG 결정 — 명시 목록, 세 축(authority·lifecycle·implementation), 대체 활성화 규칙, 스냅숏 색인, 선점형 처리, 원문 미저장, 기준선 모드, 외부 모델 결정 전.
- service-map·architecture-design 에 "1차 앞당김(ADR-0036)" 표기.
- 서비스 `CLAUDE.md`.
- `docs/handoff/clients/2026-11-04-clients-request-ai-assistant.md`: API, 인증, 202 처리 중·재조회 흐름, 오류, 상태별 예제, answerMode 표시 안내, mock 사례.
- 작업 일지.

### J. 결정이 필요한 것 (구현은 local/fake 로 진행하고 끝에 묶어 보고)
1. 외부 모델 제공자(채팅·임베딩)와 외부로 보낼 수 있는 문서 범위.
2. LAN 의 pgvector 위치(전용 컨테이너 vs 공용 이미지 교체).
3. 답변 보존 기간, 질문 원문 저장 여부.
4. 운영자 중 더 좁힐 역할.

## 검증
- **단위**:
  - 청크 분할
  - authority 판정
  - **대체 활성화: 제안 ADR 이 승인 결정을 SUPERSEDED 로 만들지 않는다(0025→0019, 0015→Commerce 회귀)** / 승인 ADR 은 명시 절만 대체(0026→0011)
  - implementationEvidence 분리(승인·미구현 표현)
  - 질문 분류 긍정·부정 20개 이상
  - 인용 검증(유효하지 않은 ID → 재생성 → 발췌 전환 → NO_GROUNDING 경로, 원문 유지 경로 없음, **부정어·숫자 불일치를 휴리스틱이 잡는 사례와 못 잡는 사례 — 못 잡는 사례는 미검증 한계로 기록**)
  - 우선순위·대체 적용(현재 질문에서 SUPERSEDED·1차 제외 제외, 이력 질문에서만 포함·표시, 제안만 있을 때 머리말, SUPERSESSION_PROPOSED 에 제안 문서 동반 인용, 임계값 미만은 지위로 안 올림)
  - 멱등 해시 정규화(공백·NFC·context 키 순서 무관, 허용 밖 context 키 무시)
  - X-Source-System 검증(토큰 src 일치, 없으면 허용 목록)
  - 근거 속 악성 지시문
  - 임베딩 캐시 키 분리
- **통합**(`pgvector/pgvector:pg17` + fake/local, 외부 호출 없음):
  - 정상 답변·인용 메타 일치(snapshotId 고정)
  - NO_GROUNDING vs 503(완료 결과 없음, 재시도 성공)
  - 같은 키 동시 요청 202, lease 만료 재선점, 최초 응답 유실 → requestId 조회
  - 다른 sourceSystem·다른 운영자 404, 고객·라이더 403, 재조회 때 현재 권한 재검사
  - 권한 밖 문서가 검색 SQL·모델 입력에 들어가지 않음
  - 재색인 멱등, 변경·삭제 반영, 실패 시 ACTIVE 유지, 동시 색인 차단, 차원 불일치 FAILED, dirty 파일 해시 기록
  - **색인 중 파일 변경 → FAILED·기존 ACTIVE 유지**, 처리 중 요청이 고정한 RETIRED 스냅숏을 `pruneSnapshots` 가 지우지 않음
  - 하드 타임아웃 < lease 기동 검사
  - **3차 검토 회귀**:
    - 권한 재검사가 POST 재응답·requestId 조회·lease 상실 재조회·`GET /{answerId}` 모두에 걸림(권한이 사라진 운영자는 저장 답변을 못 받음)
    - 대체 제안 문서 추가 조회도 access_scope 필터
    - `src` 없는 토큰이 `backoffice-web` 외 출처를 쓰면 403
    - 스냅숏 고정과 `pruneSnapshots` 동시 실행 시 고정된 스냅숏이 지워지지 않음
    - 스냅숏 삭제 뒤에도 완료 답변 권한 재검사가 됨(인용의 access_scope)
    - 발췌가 청크를 자르지 않음(개수로 제한)
    - 이력 질문에서도 EXCLUDED_FROM_PHASE1 제외
    - GET 에서 만료 lease PROCESSING → 409 재시도 안내
    - 활성 색인 부재 실패 → 422(503 아님)
  - 원문 미저장(기본)·보존 정리
- **평가**: `src/integrationTest/resources/eval/questions.yml`(대표 5 + 실제 문서 기반 15~20, 정답 근거 path·절). `RetrievalEvalIT` 가 실제 저장소 문서를 해시 임베딩으로 색인해 recall@5·MRR 을 계산하고 `build/reports/eval/` 에 쓴다 — **기준선 결과로만 보고한다.** 답변 충실도와 실제 모델의 환각·주입 방어는 `@Tag("real-model")` 로 기본 빌드에서 제외하고 미검증으로 보고한다.
- **빌드**: `dependencyInsight`(spring-ai 해석 결과), `:services:ai-assistant-service:check`, 게이트웨이 check, `config-copies.sh check`, 전체 `./gradlew check`.
- **로컬 실행**: 전용 compose up → config-server local → `ingestKnowledge` → `bootRun` → curl 로 대표 질문 5개.
- **완료 보고 세 갈래**: ① 계약·색인·권한·멱등 구현과 시험 완료 ② 로컬 검색 기준선 평가 결과 ③ 실제 모델 연결·답변 품질 미검증 항목.
