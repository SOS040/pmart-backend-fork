# RAG 구조 개편 계획 — Elasticsearch 하이브리드 검색 + 1,024토큰 청킹

> **취소(2026-11-19 사용자 "개편 일단 취소할거야")** — 이 개편(RAG 전용 Elasticsearch 하이브리드 검색·1,024토큰 청킹)은 하지 않는다. 지금 구현(pgvector)을 그대로 쓴다. 이 문서는 기록으로만 남긴다(다시 하게 되면 새 날짜 문서로).


- 작성: 2026-11-18, PC 4 (RAG 영역)
- 상태: **취소(2026-11-19 사용자)** — 계획만 세운 상태에서 멈춤. 코드·설정·배포는 아무것도 바꾸지 않았다.
- 기준: `origin/main` `674ce5ac`
- 갱신(2026-11-18): **유료 기능 제외.** ES 8.18.0 Basic에서 실제로 호출해 보고 거부된 기능(RRF·linear retriever, `text_similarity_reranker`, inference API, ML 모델)을 계획에서 뺐고, 각각 무료 대체안을 적었다(Q1·Q2). 확인 기록은 [`../verification/2026-11-18/es-basic-license-check.md`](../verification/2026-11-18/es-basic-license-check.md).

---

## 0. 요약

**바꾸는 것: 검색 저장소와 청킹 기준**
- 검색 저장소를 pgvector에서 **RAG 전용 Elasticsearch**로 옮긴다.
  - 고객용과 업무용은 인덱스를 따로 쓴다.
  - 검색 흐름은 **ES: BM25 + kNN(HNSW, cosine)** → **앱: RRF 결합** → **리랭킹(방식은 Q1에서 결정)**이다.
  - ES 내장 RRF와 ES 리랭커는 Basic에서 쓸 수 없어서(유료) 쓰지 않는다.
- 청킹 단위를 **글자 수에서 토큰 수(최대 1,024)**로 바꾼다.

**그대로 두는 것**
- 모델(Qwen3-Embedding 0.6B, Qwen3 4B, Ollama)
- 질문 분류, 인용 검사, 답변 생성 흐름, 멱등 처리, manifest
  - 단, 답변 생성에서 발췌로 바꾸는 하한(0.45)은 점수 체계가 바뀌므로 다시 정한다(Q12).
- 문서 구조 기준 분할

**구현 전에 사용자 결정이 필요한 것: 14개(§4)**
- 지금 쓰는 ES는 8.18.0이고 **Basic(무료) 기능만 쓴다**(ADR-0031 결정 7).
- 유료로 확인된 기능은 쓰지 않는다. 대체안은 Q1(리랭커)과 Q2(RRF)에서 고른다.

---

## 1. 현재 구현 (확인한 사실)

| 항목 | 현재 | 근거 |
|---|---|---|
| 임베딩 모델 | `qwen3-embedding:0.6b`, 1024차원, `num_ctx` 2048, Ollama `/api/embed` | `OllamaModels.java`, pmart-config `*-lan.yml` |
| 답변 모델 | `qwen3:4b`(Qwen3-4B-Thinking-2507), 생각 끔, JSON 출력, `num_ctx` 8192 | 위와 같음 |
| 저장소 | PC 2 `rag-pgvector`(pg17), DB 2개(`ai_assistant_service`, `customer_assistant_service`) | ADR-0051 결정 3·4 |
| 검색 점수 | 0.6 × cosine(정확 검색, ANN 인덱스 없음) + 0.4 × pg_trgm word_similarity | `ChunkStore.java:40` |
| 검색 후처리 | 후보 40개 → 합격선(LAN 0.40) → 지위·우선순위 가산점 → top-k(업무 5, 고객 4). 발췌로 바꿀 때 하한 0.45 | `Retriever`, `RankingPolicy`, pmart-config |
| 권한·생애 필터 | SQL WHERE로 먼저 거른다(가져온 뒤에 거르지 않음) | 서비스 `CLAUDE.md` 규칙 |
| 색인 버전 | `index_snapshot`. ACTIVE는 하나뿐이고, 새 색인을 다 만든 뒤 전환한다 | `V1__init.sql` |
| 청킹 | 제목 → 빈 줄 블록 → 1,500자까지 이어 붙임. 1,500자보다 큰 블록은 자르지 않음. 최소 크기·오버랩 없음 | `MarkdownChunker.java` |
| 고객 FAQ 목록 | `faq_entry`(오늘 V2로 추가, 스냅숏에 묶임) | `customer-assistant V2__faq_entry.sql` |
| 기존 ES | `es-search`(PC 3, nori 설치), `es-logs`(PC 4). 둘 다 8.18.0, 보안 꺼짐, Basic | ADR-0031·0038, `render.sh:427-450` |
| ES 호출 방식 | search-service는 ES 전용 클라이언트 의존성 없이 HTTP로 호출 | `search-service/build.gradle.kts` |
| **ES Basic에서 되는 것** | BM25(match + filter), kNN(HNSW cosine + filter), 한 요청 안의 query + knn 점수 합, `standard`·`knn`·`rescorer` retriever, query rescore | 2026-11-18 로컬 ES 8.18.0 실측 |
| **ES Basic에서 안 되는 것(유료)** | `rrf`·`linear` retriever, `text_similarity_reranker`, inference API(rerank 엔드포인트), ML 학습 모델(Elastic Rerank 포함) — 모두 403 "license is non-compliant" | 위와 같음 |

**현재 청크 측정 결과** (2026-11-18, `origin/main` 청커 + HF Qwen3 토크나이저)
- 업무용 1,682개: 1,024토큰을 넘는 조각이 **52개**, 2,048을 넘는 조각이 3개(최대 5,206)다. 넘는 조각은 **모두 표**다.
- 고객용 63개: 최대 238토큰이다. 1,024 상한의 영향이 없다.

---

## 2. 요구사항과 차이

| 요구사항 | 현재 | 차이 | 판단 |
|---|---|---|---|
| 임베딩 Qwen3-Embedding 0.6B | 같음 | 없음 | 재사용 |
| 답변 Qwen3 4B | 같음(Thinking-2507 판) | 없음. 태그만 확인 | 재사용(Q14) |
| 최소 길이 제한 없음 | 없음 | 없음 | 그대로 |
| 최대 1,024토큰, 표 포함 | 1,500자, 큰 블록은 자르지 않음 | **토큰 계산과 표 분할이 새로 필요** | 구현 |
| 구조 기준 분할 | 같음 | 없음 | 재사용 |
| 오버랩 0% | 0% | 없음 | 그대로(§3-3) |
| 기존 ES 2개 유지 | 해당 없음 | 없음 | 손대지 않음 |
| RAG 전용 ES 추가 | 없음 | **새 컨테이너**(배포는 PC 1 영역) | 요청서 + 결정(Q5·Q6) |
| 고객·업무 인덱스 분리 | DB 2개로 분리 | 인덱스 2개로 옮김 | 구현(이름은 Q7) |
| BM25 + kNN(HNSW, cosine) | cosine 정확 검색 + trigram | **바뀜** | 구현(분석기·파라미터는 Q8·Q9) |
| RRF 결합 | 가중합 | **바뀜** | ES 내장 RRF는 유료 → **앱에서 RRF 계산**(Q2) |
| ES 리랭커 | 없음 | **새로 추가** | ES 리랭커는 유료 → **무료 대체안 중 선택**(Q1) |
| pgvector 사용 안 함 | pgvector 사용 | **ADR-0051 결정 3·4와 충돌** | 새 ADR, 남는 데이터 위치 결정(Q3·Q4) |

---

## 3. 할 일 (순서대로)

### 단계 A — 확인 (코드 변경 없음)
1. **ES 8.18.0 Basic에서 되는 기능 확인 — 완료(2026-11-18).**
   - PC 4 로컬에 ES 8.18.0을 Basic 기본 상태로 띄우고(trial 안 켬) 실제로 호출했다.
   - 되는 것: BM25, kNN, filter, `standard`·`knn`·`rescorer` retriever
   - 안 되는 것(유료): `rrf`, `linear`, `text_similarity_reranker`, inference API, ML 모델
   - 기록: `docs/rag/verification/2026-11-18/es-basic-license-check.md`
   - 남은 확인: 리랭커 대체안 Q1-a를 고르면, 모델 서버(llama.cpp)에서 rerank가 실제로 되는지 PC 4에서 확인한다.
2. **Java에서 토큰을 셀 방법을 확인한다**(Q10).
   - Qwen 토크나이저와 정확히 같게 세는 방법이 필요하다. 글자 비율로 추정하면 1,024 상한을 보장할 수 없다.
3. **표 행 하나가 1,024를 넘는 경우가 있는지 실제 문서로 잰다.**
   - 지금은 큰 표 6개의 평균 행 길이(44~192토큰)만 확인했다. 최대값은 아직 재지 않았다.

### 단계 B — 결정 받기
§4의 질문을 한 번에 드리고 답을 받는다. 답을 받기 전에는 해당 작업을 하지 않는다.

### 단계 C — 문서
- **새 ADR**: RAG 저장소를 Elasticsearch로, 하이브리드 검색 + RRF + 리랭커, 1,024토큰 청킹.
  - ADR-0051 결정 3·4(pgvector)를 대체하고, ADR-0036의 해당 부분을 대체한다.
  - 운영자 색인에서 빼는 규칙(업무 정책만 넣음)에 따라 `RealDocumentsIngestTest.EXCLUDED_ADRS`에 더한다.
- `docs/rag/HANDOVER.md`, 두 서비스의 `CLAUDE.md`, `docs/rag/` 구조 문서를 갱신한다.
- `docs/architecture/service-map.md`의 RAG 저장소 서술을 갱신한다(책임은 바뀌지 않음).

### 단계 D — 청킹 (두 서비스 공통)
- `MarkdownChunker`의 한도를 글자 수에서 **토큰 수(1,024)**로 바꾼다.
  - 계산 대상은 **임베딩 입력 전체**(절 경로 + 본문)로 하자고 제안한다(Q11).
- **1,024를 넘는 블록은 자른다.**
  - 표: 행 단위로 나누고 각 조각에 머리글 행과 구분선을 반복한다.
  - 목록: 항목 단위로 나누고 도입 문장을 반복한다.
  - 문단: 문장 경계에서 나눈다.
  - 행 하나가 1,024를 넘는 경우의 처리는 단계 A-3의 측정 결과를 보고 정한다.
- **유지하는 것**: 최소 길이 제한 없음, 지위 태그 문단 단독, ADR "결정 > N" 단독, 오버랩 0.
- **시험**: 실제 manifest 문서 65개를 모두 청킹해 **모든 조각이 1,024 이하인지** 확인하는 단위 시험을 `RealDocumentsIngestTest`에 더한다. 표 분할 단위 시험(머리글 반복, 행 손실 없음)도 더한다.

### 단계 E — Elasticsearch 저장·검색 (두 서비스 공통)
- **새 어댑터**: `infrastructure`에 `ChunkStore`를 대신할 ES 어댑터를 만든다.
  - search-service처럼 Spring `RestClient` + JSON으로 호출하고, 새 의존성은 넣지 않는다.
- **인덱스와 매핑**
  - `content`: Korean 분석기(Q8)
  - `embedding`: `dense_vector`(1024, `similarity: cosine`, `index: true` → HNSW, 파라미터 Q9)
  - 필터용 keyword 필드: `access_scope`, `lifecycle_status`, `authority` 등 지금 `doc_chunk`에 있는 필드 그대로
- **색인 버전**: `index_snapshot`의 "다 만든 뒤 ACTIVE 전환"을 **스냅숏별 인덱스 + 별칭(alias) 전환**으로 바꾼다. 검색은 별칭만 본다.
- **검색 흐름** (모두 Basic 무료 기능만 씀)
  1. ES에 BM25 검색과 kNN 검색을 따로 보낸다. `_msearch` 한 번에 둘 다 보낼 수 있다.
     - 권한·생애 필터는 **BM25와 kNN 양쪽에 filter로 건다**. 가져온 뒤에 거르지 않는다는 기존 규칙을 유지한다.
  2. 앱(`Retriever`)에서 두 순위를 **RRF로 결합**한다: 점수 = Σ 1 / (k + 순위). 계산식은 ES 내장 RRF와 같다(Q2).
  3. 결합한 상위 후보에 리랭킹을 적용한다(Q1에서 고른 방식).
- **ES 다음 단계**: 지위·우선순위 반영과 합격선(`RankingPolicy`)을 ES 결과에 맞게 다시 정한다(Q12). 점수 척도가 바뀌므로 합격선 0.40·0.45는 그대로 쓸 수 없다.
- **임베딩 캐시**: Postgres `vector` 타입을 쓰므로 위치를 바꿔야 한다(Q4).

### 단계 F — 설정·시험
- pmart-config(`ai-assistant-service*.yml`, `customer-assistant-service*.yml`)와 각 서비스의 시험용 사본을 같이 고친다.
- **통합 시험**: `PgvectorContainerSupport`를 ES Testcontainers(8.18.0 + 분석기)로 바꾼다. 다음을 확인한다.
  - 고객용·업무용 인덱스가 분리되어 있는가(한쪽 문서가 다른 쪽 검색에 나오지 않음)
  - 필터가 BM25와 kNN 양쪽에 걸리는가
  - BM25·kNN(ES) → RRF(앱) → 리랭킹 순서대로 처리되는가. RRF 계산은 단위 시험으로 확인한다.
  - 유료 기능(`rrf`, `text_similarity_reranker`, inference)을 부르지 않는가. 시험용 ES도 Basic으로 띄운다.
- **로컬 개발**: `deploy/local/ai-assistant/docker-compose.yml`(RAG 영역)에 RAG ES를 더한다.
- **평가**: `RetrievalEvalIT`(문서·절 기준)로 지금 방식과 새 방식을 비교한다.
  - 실제 Ollama 임베딩으로 비교하면 업무용 약 1,700개 조각을 PC 2 Ollama로 임베딩해야 한다(약 60분, 대량). **돌리기 전에 알리고 승인을 받는다.**

### 단계 G — 배포 준비 (PC 1에 요청)
- 새 ES 컨테이너(`render.sh`, `placement.conf`, `watch.sh`, 초기화)는 **PC 1(platform) 영역**이다. PC 4에서는 고치지 않고 요청서를 쓴다.
  - 요청서 위치: `docs/architecture/service-requests/`
  - 내용: 이미지와 버전, 분석기 플러그인, 메모리, 포트 비공개, 배치 PC, 초기화
- 기존 pgvector 데이터는 바로 지우지 않는다. 새 방식이 검증된 뒤 따로 정리한다(expand → contract).
- PR에는 "**재색인 필요**"와 "**새 인프라 필요**"를 적는다.

### 단계 H — 완료 보고
- 변경한 파일, 재사용한 구성, 검증 결과(1,024 상한, 인덱스 분리, BM25·kNN → RRF → 리랭킹), 답변을 기다리는 항목을 정리한다.
- **실행하지 않은 시험과 배포는 완료로 쓰지 않는다.**

### 3-3. 오버랩
**기본 0%를 유지하고, 10%는 제안하지 않는다.**
- 자르게 되는 블록(52개)은 모두 표다. 표는 머리글 행을 반복하는 쪽이 행을 겹치는 것보다 맥락을 정확히 전한다.
- 나머지 조각은 문단 경계에서 나뉘어 문장이 끊기지 않는다.
- 단계 A-3에서 **긴 문단이나 목록을 문장 중간에서 잘라야 하는 경우**가 나오면, 그 대상에만 10%를 적용할지 따로 여쭙는다.

---

## 4. 결정이 필요한 항목

각 항목에 현재 구현, 결정할 것, 선택지의 영향을 적었다.

| # | 항목 | 현재 | 선택지와 영향 |
|---|---|---|---|
| **Q1** | 리랭커 (ES 리랭커는 유료라 제외) | 없음 | **(a) 자체 호스팅 리랭커 모델 + 앱에서 호출**: 예) bge-reranker-v2-m3 또는 Qwen3-Reranker-0.6B를 llama.cpp `llama-server --reranking`(`/v1/rerank`)로 띄움. 무료이고 정확도가 가장 좋지만, **세 번째 모델과 모델 서버가 하나 더 늘어난다**(Ollama에는 rerank API가 없음, 확인 필요). Qwen3-Reranker는 llama.cpp 지원이 불안정하다는 보고가 있다<br>**(b) 지금 답변 모델(qwen3:4b)로 다시 매기기**: 새 모델이 없지만, 질문마다 생성 호출이 늘어 느리고 GPU를 답변과 나눠 쓴다<br>**(c) ES 무료 rescore**: `rescorer` retriever / query rescore(구문 일치, `script_score` cosine 재계산). 무료이고 추가 서버가 없지만, 학습된 리랭커가 아니라 규칙 재정렬이다<br>**(d) 리랭커 없이** RRF + 지금의 지위 가산점만 사용 |
| **Q2** | RRF (ES 내장 RRF는 유료라 제외) | 가중합 | **(a) 앱에서 RRF 계산(권함)**: ES에서 BM25와 kNN을 따로 받아 `Retriever`에서 RRF 식을 적용한다. 식은 ES 내장과 같고 무료다. `k`(rank_constant)·창 크기는 Q9<br>(b) ES 한 요청 안의 query + knn 점수 합(boost): 무료지만 BM25 점수에 상한이 없어 척도가 맞지 않는다. RRF가 아니다 |
| **Q3** | pgvector를 빼면 남는 표의 위치 | `policy_answer`, `customer_answer`, `index_snapshot`, `index_source_file`, `faq_entry`가 rag-pgvector에 있음 | (a) 같은 컨테이너를 pgvector 없는 Postgres로 유지 → 변경 최소<br>(b) 공용 Postgres로 이전 → 컨테이너 하나 줄지만 이전 작업 필요<br>(c) ES로 이전 → 멱등 선점·조건부 갱신이 어려워 **권하지 않음** |
| **Q4** | 임베딩 캐시 | Postgres `vector` | (a) Postgres `real[]`로 → 재색인 때 재임베딩을 피함<br>(b) 캐시 없앰 → 재색인마다 전체 임베딩(약 60분) |
| **Q5** | RAG ES 배치 PC와 자원 | 없음 | PC 2(RAG 묶음과 같은 PC, 메모리 확인 필요) / 다른 PC. Q1-a를 고르면 리랭커 모델 서버의 자리(GPU·메모리)도 정해야 한다. 배포는 PC 1이 한다 |
| **Q6** | 보안·포트 | 기존 ES는 보안 끔, LAN 공개 | 업무용 인덱스에 내부 정책 문서가 들어간다. **rag-pgvector처럼 LAN 포트를 열지 않기**를 권함 |
| **Q7** | 인덱스·별칭 이름 | 없음 | 임의로 정하지 않는다. 예: 별칭 `rag-ops`/`rag-customer` + 스냅숏별 실제 인덱스 |
| **Q8** | BM25 한국어 분석기 | trigram | nori만 / nori + n-gram(상품 검색과 같은 방식) / n-gram만. nori는 플러그인 설치가 필요(검색 ES는 시작 때 인터넷으로 설치) |
| **Q9** | 검색 파라미터 | 후보 40, top-k 5/4 | kNN `k`, `num_candidates`, HNSW `m`/`ef_construction`, BM25·kNN 각각 가져올 개수, 앱 RRF의 `k`(흔히 60), 리랭킹에 넘길 후보 수. 기본값을 쓸지 정해야 한다 |
| **Q10** | Java 토큰 계산 방법 | 글자 수 | (a) HF 토크나이저 Java 바인딩(새 의존성, 네이티브 라이브러리) / (b) Qwen `tokenizer.json`을 읽는 자체 BPE 구현 / (c) Ollama에서 토큰 수를 받음(색인 때 추가 호출). 정확도와 의존성 사이의 선택 |
| **Q11** | 1,024에 포함하는 범위 | — | 본문만 / **임베딩 입력 전체(절 경로 + 본문)**(권함) / 앞으로 문서 제목까지 |
| **Q12** | 지위 가산점과 합격선 | 혼합 점수에 가산 후 0.40, 발췌 하한 0.45 | 리랭커 점수에 그대로 가산 / 리랭크 뒤 동점 정리에만 사용. 합격선은 리랭커 점수 기준으로 평가를 다시 해서 정해야 한다 |
| **Q13** | "장애 분석" 문서 | 업무용 색인에 장애 분석 문서 없음 | 어떤 문서를 넣을지(manifest 추가)는 사용자가 정한다. 정하지 않으면 이번 범위에서 뺀다 |
| **Q14** | 모델 태그 고정 | `qwen3:4b`(digest 고정 안 함) | 지금 판(Thinking-2507)으로 digest를 고정할지 |

---

## 5. 지키는 규칙
- **배포·인프라·재색인**: LAN 배포와 재색인은 하지 않는다(PC 1만). `deploy/lan`은 요청서로만 다룬다.
- **PC 2 Ollama**: 대량 임베딩이나 전체 평가 전에는 알린다. `pull`·`rm`은 하지 않는다.
- **인증 코드**: 인증·토큰 관련 코드는 건드리지 않는다.
- **PR**: main에 push하지 않는다. 포크 `rag/<주제>` 브랜치로 PR을 낸다.
- **작업 폴더**: 같은 폴더를 다른 세션이 쓰므로 worktree에서 작업한다.
- **로그**: 질문 원문과 개인정보는 로그에 남기지 않는다.
