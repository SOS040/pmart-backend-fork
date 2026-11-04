# ai-assistant-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `ai-assistant-service` 절과 ADR-0036 이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/ai-assistant-service.yaml`(1.0.0) → `com.pmart.assistant.api.generated`. `api/AssistantController` 가 구현한다.
- 이벤트: 발행·구독 없음(1차). common-messaging 을 쓰지 않는다.

## 패키지
- `domain`: 지위(Authority)·생애(Lifecycle)·의도 분류·멱등 해시·출처 검사·순위·발췌·인용 검사 — 프레임워크 없음.
- `infrastructure`: JDBC 저장(`SnapshotStore`·`ChunkStore`·`AnswerStore`), 임베딩(`LocalHashEmbeddingModel`), 색인(`ingest/`).
- `application`: `PolicyQuestionService`(선점·고정·완료), `Retriever`, 답변 생성(발췌·생성), `KnowledgeJobs`(색인·정리·보존).

## 반드시 지킬 것
- **모델·임베딩 호출은 트랜잭션 밖에서 한다.** 선점 뒤의 쓰기(스냅숏 고정·완료·실패)는 모두 `attempt`·`state='PROCESSING'` 조건부다. 0건이면 자기 결과를 버리고 저장 상태로 응답한다. 공통 `RequestIdempotency` 는 쓰지 않는다.
- 저장 답변을 돌려주는 경로는 모두 `PolicyQuestionService.stored(...)` 를 지난다(현재 권한 재검사). 새 경로를 만들어도 이 규칙을 따른다.
- 검색 범위(접근 범위·생애 제외)는 SQL WHERE 로 먼저 건다. 권한 밖 문서를 가져와서 걸러내지 않는다.
- 잠금은 둘이다. `AdvisoryLocks.INGEST`(세션, 색인 전체)와 `AdvisoryLocks.SNAPSHOT`(트랜잭션, 고정=공유 / 전환·정리=배타). 키를 섞지 않는다.
- 질문·문서 본문·토큰을 로그에 남기지 않는다(답변 ID·상태·소요 시간·청크 수만).
- 외부 모델 제공자 모듈은 결정 전이라 넣지 않는다(ADR-0036 결정 필요 1). `provider=fake` 는 시험 전용이다.
- 색인 대상은 `src/main/resources/knowledge/manifest.yml` 의 명시 목록뿐이다. 문서를 더하면 목록에 넣는다. 새 ADR 도 넣어야 한다 — `RealDocumentsIngestTest` 가 ADR 개수를 대조한다.

## 데이터
- `V1__init.sql`: `index_snapshot`·`index_source_file`·`doc_chunk`(vector·pg_trgm)·`embedding_cache`·`policy_answer`. 적용된 파일은 수정하지 않는다.
- pgvector 가 필요하다. 로컬은 `deploy/local/ai-assistant/docker-compose.yml`(15433), 시험은 `PgvectorContainerSupport`(`pgvector/pgvector:pg17`).

## 실행
```bash
./gradlew :services:ai-assistant-service:check     # 단위 + 통합(pgvector) + 검색 기준선 평가(build/reports/eval/)
docker compose -f deploy/local/ai-assistant/docker-compose.yml up -d
./gradlew :platform-services:config-server:bootRun --args='--spring.profiles.active=local'
./gradlew :services:ai-assistant-service:ingestKnowledge -Prepo=$(pwd)      # 색인(기동 때는 하지 않는다)
./gradlew :services:ai-assistant-service:bootRun --args='--spring.profiles.active=local'
./gradlew :services:ai-assistant-service:pruneSnapshots                     # RETIRED·FAILED 수동 정리
```
로컬 포트 8508.

## 상태
- 내 질문 목록 `GET /assistant/question-history`(BR-46, 계약 1.1.0) — 본인·출처 범위, 완료 답변은 현재 권한 재검사 후만.
- 1차 구현(2026-11-04, ADR-0036): 기준선(해시 임베딩 + 발췌). LAN 배포 안 함. 외부 모델 연결 안 함.
