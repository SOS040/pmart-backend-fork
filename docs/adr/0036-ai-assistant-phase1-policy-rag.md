# ADR-0036 ai-assistant-service 1차 — 내부 운영자 정책·업무 절차 RAG를 앞당긴다(외부 모델 없이 기준선)

- 상태: 제안 (2026-11-04, 사용자 요청 "3단계로 미뤄 둔 RAG 중 1차 범위를 앞당겨 구현" — 계획은 Codex 검토 1·2·3차 반영, `codex-opinion/2026-11-04-ai-assistant-plan.md`)
- 관련: `contracts/openapi/ai-assistant-service.yaml`(1.0.0), `services/ai-assistant-service/`, `docs/architecture/service-map.md` §8, ADR-0029(설정 저장소)

## 배경
- `ai-assistant-service` 는 3단계 서비스였다. 본사·센터 운영자가 정책·업무 절차를 물으면 저장소 문서를 근거로 답하는 기능을 먼저 만든다.
- 업무 문서 대부분은 "설계안"이고, 문단 태그([확정 정책]·[채택 설계]·[설계안]·[가정]·[결정 필요])와 ADR 상태(승인·제안)로 지위가 갈린다. 문서끼리 충돌하면 `domain-map.md:35` 우선순위를 따른다.
- 외부 모델 제공자와 외부로 보낼 수 있는 문서 범위는 아직 정하지 않았다. 그래서 1차는 외부 호출 없이 파이프라인·계약·권한·멱등을 먼저 검증한다.

## 결정
1. **범위**: 문서 질의응답과 근거 표시까지만 한다. 특정 대상의 현재 상태 질문은 `REALTIME_DATA_REQUIRED`, 실행 요청은 `OUT_OF_SCOPE` 다(규칙 기반 의도 분류, 모델 없음). 다른 서비스 DB·API 는 부르지 않는다. 클라이언트도 수정하지 않는다.
2. **명시 목록 색인**: `knowledge/manifest.yml` 에 적은 문서만 색인한다(업무 명세 8, flows 4, 상태표 8, overview·domain-map·glossary·service-map, ADR 전부). worklog·handoff·코드·비밀은 넣지 않는다. 우선순위 등급은 domain-map.md:35 그대로 둔다.
3. **세 축을 따로 둔다**: 지위(authority), 생애(lifecycle), 구현 기록(implementationEvidence).
   - 지위: CONFIRMED · ADOPTED_DESIGN · PROPOSAL · ASSUMPTION · UNDECIDED · EXTERNAL_REFERENCE. 문서 기본값을 절·문단 태그가 덮는다. ADR 은 상태 줄로 정한다.
   - 생애: ACTIVE · SUPERSEDED · SUPERSESSION_PROPOSED · EXCLUDED_FROM_PHASE1.
   - 구현 기록: "구현 상태 (YYYY-MM-DD…)" 절의 기준일이다. 지위를 바꾸지 않는다.
4. **대체 활성화 규칙**: 대체하는 ADR 이 **승인**이고 **결정 번호가 명시**됐을 때만, 명시된 결정 청크가 SUPERSEDED 가 된다(예: 0026→0011 결정 3·5). 제안·초안 ADR 이거나 번호가 없으면 대상은 SUPERSESSION_PROPOSED 로 표시만 하고 지위를 유지한다(예: 0025→0019, 0024→0019). 규칙에 맞지 않는 예외는 근거와 함께 manifest `overrides` 에 적는다(0015→Commerce "고객 분류는 1·2단계": CONFIRMED 유지 + 대체 제안 표시). 대상마다 **첫 언급**의 번호만 읽는다. 그래서 같은 줄의 "결정 1·2 는 유지"를 대체로 읽지 않는다.
5. **검색 규칙**
   - 고정된 스냅숏, 호출자 허용 접근 범위, 생애 제외를 SQL WHERE 로 먼저 건다. 현재 질문은 SUPERSEDED·EXCLUDED_FROM_PHASE1 을 뺀다. 이력 질문은 SUPERSEDED 만 다시 넣는다.
   - 혼합 유사도는 pgvector 코사인과 pg_trgm word_similarity 를 섞은 값이다. 임계값을 넘은 후보끼리만 지위·우선순위 가중으로 정렬한다.
   - 확정·채택 근거가 없으면 답 앞에 "확정 정책 근거 없음 — 아래는 설계안·미결정 기준"을 붙인다.
   - SUPERSESSION_PROPOSED 근거에는 제안 문서를 같은 접근 범위 필터로 함께 인용한다.
6. **스냅숏 색인**: 통제된 작업 `ingestKnowledge -Prepo=` 로만 색인한다. 기동 때는 색인하지 않는다.
   - 잠금은 둘이다. 색인 전체 동안 쥐는 세션 잠금 `INGEST` 와, 짧게 쥐는 트랜잭션 잠금 `SNAPSHOT`(요청의 스냅숏 고정은 공유, 활성 전환·정리는 배타)이다. 재색인 중에도 질문은 기존 ACTIVE 로 답한다.
   - 파일 바이트는 한 번만 읽고 그 바이트로 해시와 청크를 만든다. 끝에서 다시 해시해 바뀐 파일이 있으면 FAILED 다. 차원도 검증한다.
   - RETIRED 스냅숏은 자동으로 지우지 않는다. 수동 `pruneSnapshots` 가 유예가 지나고 고정한 PROCESSING 요청이 없는 것만 지운다.
   - 임베딩 캐시 키는 (모델, 버전, 차원, 전처리 해시, 본문 해시)다.
7. **선점형 처리**: 공통 `RequestIdempotency` 는 쓰지 않는다. 모델 호출을 긴 트랜잭션 밖에 두기 위해서다. `policy_answer` 에서 claim/lease 로 처리한다.
   - 선점 뒤의 스냅숏 고정·완료·실패는 모두 `attempt`·`state='PROCESSING'` 조건부 UPDATE 다. 0건이면 lease 를 잃은 것이므로 자기 결과를 버리고 저장 상태로 응답한다.
   - 하드 타임아웃은 lease 보다 짧아야 한다(기동 검사).
   - 실패는 `last_error_code` 로 422 CONFIG_REQUIRED 와 503 DEPENDENCY_UNAVAILABLE 을 가른다. GET 에서 실패 행이나 lease 가 만료된 행은 409 INVALID_STATE(retryable)로 돌려준다.
8. **출처·권한**
   - 토큰 `src` 가 있으면 X-Source-System 과 같아야 한다. 없으면 `backoffice-web` 하나로 고정한다.
   - 저장 답변을 돌려주는 모든 경로에서 현재 권한을 재검사한다. 지금도 운영자인지, 본인인지, 인용 근거의 접근 범위를 갖는지 본다. 인용 jsonb 에 청크별 접근 범위를 저장하므로 스냅숏을 정리한 뒤에도 재검사할 수 있다.
9. **원문 미저장·보존**
   - 질문 원문은 기본 저장하지 않는다(`request_hash` 만).
   - 보존 기간이 설정되지 않으면 지우지 않는다.
   - 질문·문서 본문·토큰은 로그에 남기지 않는다.
10. **기준선 모드**: `provider=local` 은 LocalHashEmbeddingModel(문자 2·3-gram 해시, 384차원)과 발췌 답변이다. 발췌는 온전한 청크 단위이고, 길이는 개수로 줄여 맞춘다. 응답의 `answerMode=BASELINE_EXTRACTIVE`·`retrievalMode=BASELINE_HASH` 로 기준선임을 드러낸다.
    - 생성 경로(Spring AI `ChatModel`)는 `provider=fake` 시험으로만 검증한다. 인용 검사(ID 유효성, 그리고 n-gram·부정어·숫자 휴리스틱)가 실패하면 1회 재생성하고, 그래도 실패하면 발췌로, 근거도 없으면 NO_GROUNDING 이다. 원문을 유지하는 경로는 없다.
11. **의존성**: Spring AI 2.0.2(`spring-ai-bom`, `spring-ai-model`만)를 이 서비스에만 둔다. 제공자 모듈은 아직 없다.
    - pgvector 가 필요해서 로컬은 전용 compose(`deploy/local/ai-assistant`, `pgvector/pgvector:pg17`, 15433)를 쓴다. 공용 Postgres·init-db.sql 은 바꾸지 않는다.

## 결정 필요(구현은 local/fake 로 진행)
1. 외부 모델 제공자(채팅·임베딩)와 외부로 보낼 수 있는 문서 범위.
2. LAN 의 pgvector 위치(전용 컨테이너 vs 공용 이미지 교체) — 그 전에는 LAN 배포하지 않는다.
3. 답변 보존 기간, 질문 원문 저장 여부.
4. 운영자 중 더 좁힐 역할.

## 결과
- 지금 검증된 것은 계약·색인·지위/생애 판정·권한·멱등·스냅숏 수명이다(단위·통합 시험). 검색 품질은 해시 기준선 수치(`RetrievalEvalIT` → `build/reports/eval/retrieval-baseline.md`)뿐이다.
- 의미 검색 품질, 생성 답변의 충실도, 환각·주입 방어는 실제 모델 평가 전까지 미검증이다.
