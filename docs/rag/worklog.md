# RAG 작업 일지

- 대상: `ai-assistant-service`(RAG, [`README.md`](README.md)).
- 2026-11-04~05 기록은 일반 작업 일지(`docs/worklog/2026-11-04.md`·`2026-11-05.md`)에서 RAG 부분만 모아 다시 정리했다. 원래 기록은 그 일지에 남아 있다.
- 앞으로 RAG 작업은 이 파일에 쓴다.
- 검토 원문은 [`codex-opinion/`](codex-opinion/README.md), 검증 자료는 [`verification/`](verification/2026-11-05/README.md)에 있다.

---

## 2026-11-04

### 계획 (검토 1~3차 반영)
- 요청: 3단계로 미뤄 둔 RAG 중 1차 범위를 앞당겨 구현. 범위는 내부 운영자의 정책·업무 절차 질의응답과 근거 표시다. 실시간 조회·명령·클라이언트 수정·LAN 배포·외부 모델 연결은 빼고 시작했다.
- 계획을 쓰고 Codex 검토 10개 → 2차 7개 → 3차 6개를 반영했다. 1차의 4·5·6번(트랜잭션 경계, 인용 실패 처리, FORBIDDEN_SCOPE 추론)은 계획의 결함이었다.
  - 계획: `codex-opinion/2026-11-04-ai-assistant-plan.md`
  - 원문: `codex-opinion/…review-1~3.md`

### 15:30~17:10 1차 구현 (ADR-0036)
- 정리 3가지:
  - 실패는 오류 코드별 422/503
  - 출처는 `backoffice-web` 고정, 이력 검색은 SUPERSEDED 만
  - 스냅숏 기록도 선점 조건부, INGEST 세션 잠금과 SNAPSHOT 전환 잠금 분리
- 빌드: `settings.gradle.kts` 등록, 카탈로그 `spring-ai 2.0.2`(bom·`spring-ai-model`, 이 서비스만). 해석 결과 Boot 4.0.9 · spring-core 7.0.10(다른 서비스와 같음).
- 계약 `ai-assistant-service.yaml` 1.0.0.
- `V1__init.sql`: vector·pg_trgm, 표 `index_snapshot`·`index_source_file`·`doc_chunk`·`embedding_cache`·`policy_answer`.
- domain: 지위·생애·의도 분류·멱등 해시·출처 검사·순위·발췌·인용 검사.
- infrastructure: JDBC 저장 3개, `LocalHashEmbeddingModel`, 색인(manifest·청크 분할·대체 판정·적재).
- application:
  - `PolicyQuestionService`: 선점 → 분류 → 스냅숏 고정(공유 잠금, 조건부) → 트랜잭션 밖 검색·답변(하드 타임아웃) → 조건부 완료/실패
  - 발췌·생성 답변
  - `KnowledgeJobs`: 색인·정리·보존
- 색인 대상 `knowledge/manifest.yml`: 60 문서, ADR-0015 → Commerce override. Gradle `ingestKnowledge -Prepo=`·`pruneSnapshots`.
- 설정:
  - 설정 저장소 `ai-assistant-service{,-local,-lan}.yml`(8508)
  - 게이트웨이 `/api/v1/assistant/**`
  - lan DB 비밀번호는 비워 둠(배포 결정 뒤 `{cipher}`)
- 로컬 전용 `deploy/local/ai-assistant/docker-compose.yml`(pgvector/pgvector:pg17, 15433). 공용 compose 는 그대로 뒀다.
- 문서: ADR-0036(제안), service-map·architecture-design 표기, 서비스 CLAUDE.md, 클라이언트 안내 `docs/handoff/clients/2026-11-04-clients-request-ai-assistant.md`.
- 시행착오:
  - 통합 시험 컨테이너를 `@Container` 로 두면 시험 클래스마다 멈춰, 공유 컨텍스트가 꺼진 포트를 잡았다 → 정적 start 로 바꿨다.
  - jsonb::text 의 `": "` 공백 형식이 시험 치환 SQL 과 맞지 않았다 → 정규식으로 고쳤다.
- 시험(실행함):
  - `:services:ai-assistant-service:check` 통과 — 단위 83, 통합 29
  - 게이트웨이 check 통과
  - `config-copies.sh check` 통과
- 검색 기준선: 문서 60·청크 1824, 질문 25, recall@5 0.76 · MRR 0.628(**문서 경로 기준** — 11-05 명시).
- 커밋·push `a387410`(18:00). pmart-config `7676cb8` 은 같은 날 18:05 LAN 배포 작업 때 push 했다(서비스는 LAN 에 배포하지 않음).

### 17:20~17:50 검증 마무리 (외부 검토 요청)
- 전체 `./gradlew check --continue`(13분 47초): mfc-capacity `ServiceBootIT` 1건 실패(Kafka 시험 컨테이너 종료 코드 126). 그 서비스만 재실행해 6/6 통과. 나머지는 모두 통과.
- `dependencyInsight`: spring-ai-bom·model·commons·template-st 2.0.2, spring-boot-starter 4.0.9, spring-core 7.0.10. `config-copies.sh check` 통과.
- 로컬 HTTP(인증 우회 없음):
  - 구성: 로컬 postgres·kafka + config-server·auth-service·ai-assistant(local)
  - 인증: 로컬 전용 관리자 `admin`/`pmart-local`(ADR-0018:23) 토큰
  - 대표 질문 R1~R5 모두 200 ANSWERED(발췌 기준선)
  - 재전송 같은 answerId, requestId 회수 200, 토큰 없음 401, 다른 출처 403
- 미검증:
  - 게이트웨이 경유 호출
  - `src` 있는 토큰의 HTTP(통합 시험만)
  - 실제 모델(의미 검색·생성 충실도·환각·주입 방어)

### 20:30~21:30 BR-46 질문 목록 (클라이언트 요청 BR-44~48 묶음)
- 근거:
  - 클라이언트 요청서 `../pmart-clients/docs/handoff/clients/2026-11-04-backend-ai-assistant-contract.md` §4
  - 계약 1.0.0 대조 결과 남은 항목: 질문 목록·원문 열람·상태 확인 경로·LAN 배포
- `GET /assistant/question-history`(계약 1.1.0): 본인·출처 범위, 완료 답변은 현재 권한 재검사.
- 원문 열람 없음, actuator 비공개, LAN 배포 보류.
- 회신 `docs/handoff/clients/2026-11-04-clients-reply-br44-br48.md`.
- 커밋·push `bcb2fd4`(20:46).

---

## 2026-11-05

### 오전 검증 보강 (검토 4·5)
- 09:23 현재 HEAD 에서 다시 실행:
  - ai-assistant check 통과(단위 83, 통합 30 — BR-46 시험 포함)
  - dependencyInsight 같은 결과
  - config-copies 통과
- 09:35 전체 `./gradlew check --continue`: 12분 7초, **첫 실행 통과**(재실행 없음). 수정 뒤 126 은 다시 나지 않았다.
- 증거 정리:
  - 세 커밋(`a387410`·`bcb2fd4`·pmart-config `7676cb8`)은 11-04 에 이미 push 됐다. "커밋 안 함"은 검증 단계에서 새 커밋이 없다는 뜻이었다.
  - 두 저장소 모두 작업 트리가 깨끗하고 origin 과 차이가 없었다.
  - BR-46 근거와 diff, Kafka 수정 diff·근거를 정리했다.
  - 실패표의 정답 경로를 전체 이름으로 복구했다.
- 정리 중 **BR-46 페이지 결함 발견**: 권한 재검사로 빠지는 행이 있으면 다음 쪽 행이 누락될 수 있었다. 기능 추가 금지 지시가 있어 보고만 했다.

### 오후 검토 6 반영
- **BR-46 페이지 결함 수정**:
  - 페이지 계산을 `domain/HistoryPager` 로 분리했다.
  - 보이는 행을 size+1 개째까지 찾아 그때만 nextCursor 를 준다. 숨김 행이 연속돼도 누락·중복이 없다.
  - 회귀 시험:
    - `HistoryPagerTest` 8개: A·B·C·D, 연속 숨김·배치 경계, 256 조합 전수 등
    - `PolicyQuestionFlowIT.questionHistoryKeepsPagingWhenTheNewestAnswerIsHidden`
    - 통합 시험은 수정 전 코드로 실패 → 수정 후 통과를 확인했다.
- **검색 평가**:
  - 기존 지표를 "문서 경로 기준"으로 명시했다(결과 본문 그대로).
  - "절 기준"을 추가했다: recall@5 0.60 · MRR 0.387.
  - 정답 절은 원문을 읽어 expected 문서 안에서만 정했다. 검색 결과에 맞춰 넓히지 않았다.
  - 정답 절이 색인에 있는지 시험이 대조한다.
- **Kafka 시험 컨테이너 수정(`869a6b1`)**: "추정 원인에 대한 방어적 수정"으로 기록했다. 재현하지 못했고 당시 로그가 없다.
- `:services:ai-assistant-service:check` 통과(단위 91, 통합 31). 공용 코드 변경이 없어 전체 빌드는 다시 돌리지 않았다.
- 검토 자료를 `verification/2026-11-05/` 에 모았다(임시 폴더·build 에만 있던 로그·diff·평가 결과).
- RAG 문서 폴더 `docs/rag/` 를 만들었다(사용자 요청). `codex-opinion/` 을 저장소 루트에서 이 폴더로 옮겼고, RAG 작업 일지를 이 파일로 분리했다.
- 커밋·push·LAN 배포·외부 모델 연결은 보류했다.

## 2026-11-09
- 사용자 결정: 고객용으로도 확대(D1 재확인), **pgvector 를 백오피스용·고객용 따로**(C7 — 앞의 추천 "함께, 청크별 접근 범위로 거름"을 대체), 임베딩은 Qwen3-Embedding-0.6B·BGE-M3 를 우리 문서로 비교 뒤 선택(D3). `planning/2026-11-07-references-review.md` 결정 표에 반영.
- 사용자 결정: 추천대로 서비스 분리(고객용 `customer-assistant-service` 새로 만듦), RAG 전용 pgvector 컨테이너 하나에 DB 둘, LAN 배치 pc2(PC 를 한 대 더 들일 수 있음), 모델은 Ollama. → ADR-0051(승인), service-map §8·architecture-design 50개, CLAUDE.md.
- LAN 확인(읽기만): 세 PC PostgreSQL 모두 pgvector 없음, PC 넷 같은 사양(i5-11400F·32GB·RTX 2060 6GB), 컨테이너 메모리 pc2 7.8·pc3 11.7·pc4 10.2GB.
- 색인 목록(`manifest.yml`)에 빠져 있던 ADR-0037~0050 과 새 0051 추가(RealDocumentsIngestTest 가 ADR 개수 대조). 같은 시험의 낡은 기대값(주문 사가 문서 구현 날짜가 모두 10-31) 을 절별(1.4 = 10-31, 2.0.1 = 11-07)로 고침. ai-assistant check 통과(단위 91, 통합 31).
- pc2 Docker GPU 확인: 기존 이미지로 `nvidia-smi` 일회 컨테이너(`--rm`) — RTX 2060 6144MiB(사용 861MiB), CUDA 13.3. Ollama 를 컨테이너로 GPU 에서 돌릴 수 있다.

## 남은 것
- ADR-0036 결정 4건(외부 모델·외부 전송 범위, LAN pgvector 위치, 답변 보존·원문 저장, 운영자 역할).
- 결정 뒤: pgvector 배치, LAN 배포, 실제 모델 평가(의미 검색·생성 충실도·환각·주입 방어).
- 평가: 문서는 맞혔지만 절은 못 맞힌 Q01·Q04·Q11·Q20, 놓친 Q05·Q10·Q12~Q15(해시 기준선 한계).
