# 2026-11-07 참고 원문 3개 검토

원문: [`../references/2026-11-07/`](../references/2026-11-07/). 대조 기준: ADR-0036(1차 = 내부 운영자 정책·업무 절차 질의응답), `services/ai-assistant-service` 현재 코드.

## 1. 구성도 `spring ai rag arch.svg`
그림 구조: Client(Flutter/React/Web) → API Gateway → [ChatController/ChatClient — RAG Orchestrator] → 왼쪽 열 VectorStore → EmbeddingModel → Vector DB(PGVector/Qdrant), 오른쪽 열 ChatModel → LLM API Adapter → LLM Provider(OpenAI/Ollama). 아래 문서 적재 PDF/DB/Web → ETL/Chunking/Embedding → VectorStore 저장 ┄→ Vector DB.

| # | 문제 | 고칠 방향 |
|---|---|---|
| 1 | VectorStore → EmbeddingModel → Vector DB 직렬 | VectorStore 가 임베딩 모델과 벡터 DB 를 각각 부른다 |
| 2 | 검색 결과가 생성으로 가는 선 없음(두 열이 끝까지 따로) | 검색 결과 → 근거 묶기·프롬프트 → 생성 |
| 3 | 오케스트레이터 화살표 하나가 두 상자 사이를 가리킴 | ① 검색 ② 생성, 순서 있는 화살표 둘 |
| 4 | 적재의 Embedding 이 위 EmbeddingModel 과 따로 | 같은 모델(스냅숏에 모델 정보 기록)로 잇는다 |
| 5 | "LLM API Adapter" 는 Spring AI 구성요소가 아님 | ChatModel 구현체(예 `OllamaChatModel`)가 그 역할. 상자 뺀다 |
| 6 | Controller → Orchestrator 선에 화살표 머리 없음 | 머리 단다 |
| 7 | 기본 글자색 `rgb(237,237,237)` — 밝은 배경에서 제목·검정 화살표가 안 보임 | 밝은·어두운 화면 둘 다 보이게 |

## 2. 검토 글 `spring_ai_rag_architecture_review.md`
- 위 1·2(직렬 표현, 검색 → 생성 누락)는 맞다. 적재 단계 세분·메타데이터·권한 통제·검색 실패 처리·Elasticsearch 와 역할 분리도 맞고, **이미 구현돼 있다**: 명시 목록(`manifest.yml`)·스냅숏 단위 적재/전환/정리, 지위·생애·접근 범위 메타데이터, SQL WHERE 로 권한 먼저, 저장 답변 반환 때 권한 재검사, 근거 없음·인용 불일치 거절, 1차는 실시간 조회 안 함(`REALTIME_DATA_REQUIRED`).
- 받아들이지 않는 것:
  - `QuestionAnswerAdvisor`/`RetrievalAugmentationAdvisor` 사용 — 접근 범위·생애·인용 검사를 직접 통제해야 해서 지금의 `Retriever` 를 유지한다.
  - 수정안 다이어그램의 Client "React / Web" — 우리 앱은 Flutter(Android). 원래 그림이 맞다.
  - `rag-service` 이름 — 서비스 이름은 `ai-assistant-service`("RAG" 는 별칭).
- 3~6(Retrieval/Context/Generation 분리, 권한 통제)을 빠뜨린 그림 문제로 본 건 맞지만, 우리 구현에서 필요한 장치(인용 검사, 답변 기록·중복 요청, 저장 답변 권한 재검사)는 원문 어디에도 없다 — 새 그림에 넣는다.

## 3. 모델 추천 `baropick_rag_free_models.md`
- **범위 차이**: 고객앱 + 백오피스 양쪽 챗봇을 가정한다. ADR-0036 1차는 운영자 전용이고, 색인 문서도 내부 문서뿐이다(고객 공개 정책 문서 없음). 고객용은 새 업무 범위 → 결정 대기 D1.
- **임베딩**: 1순위 Qwen3-Embedding-0.6B, 2순위 BGE-M3. 둘 다 무료·Ollama 제공. 둘 다 `RetrievalEvalIT` 로 우리 문서에서 비교해 고른다(D3). BGE-M3 라이선스는 MIT(원문이 맞음). 어느 쪽이든 차원 384 → 1024(확인 필요) 변경 = 새 마이그레이션 + 재색인.
- **대화 모델(Qwen3 4B/8B/14B, Gemma3)**: LAN PC 의 GPU·남은 메모리부터 확인해야 한다(CPU 만이면 8B 는 답 하나에 수십 초 가능). Qwen3 는 생각(thinking) 단계를 꺼야 지연이 준다. 모델 서버 Ollama·Spring AI Ollama 모듈은 새 도구 → D2.
- 고객/내부 문서 분리 저장 제안 — 지금은 같은 표에서 접근 범위를 WHERE 로 거른다. 고객용을 열 때(D1) 다시 본다.

## 4. 결정
| # | 내용 | 결정 |
|---|---|---|
| D1 | 고객앱에도 RAG 를 연다. 그림은 운영자 경로와 분리 → [`2026-11-07-rag-architecture-customer.svg`](2026-11-07-rag-architecture-customer.svg)(계획). ADR-0036(운영자 전용 1차)을 넓히는 것이라 구현 전에 새 ADR 이 필요하다 | **결정** 2026-11-07 사용자 |
| D4 | 새 구성도 → [`2026-11-07-rag-architecture-backoffice.svg`](2026-11-07-rag-architecture-backoffice.svg)(처음 이름 `-architecture.svg`). 코드(`PolicyQuestionService.ask`·`Retriever`·`KnowledgeIngestor`·`SnapshotStore`)와 대조해 그림 | **결정·완료** 2026-11-07 사용자 |
| — | 벡터 DB 는 pgvector 만. Qdrant 는 쓰지 않는다(문서 수천 청크 규모, 권한·키워드·스냅숏을 한 SQL·한 DB 잠금으로 묶고 있음). 수백만 규모나 검색 속도 문제가 생기면 다시 본다 | **결정** 2026-11-07 사용자 |
| D1 재확인 | 고객용으로도 확대한다 | **결정** 2026-11-09 사용자 |
| C7 | **pgvector 를 백오피스용·고객용 따로 둔다** — 위 "같은 표에서 WHERE 로 거른다"·고객 보고서의 "같은 DB" 를 대체. 고객용에는 고객 공개 문서만 들어가므로 내부 문서가 고객 답에 섞일 길이 저장소 단계에서 없다. 따로 두는 방식(컨테이너 둘 / 한 컨테이너 안 DB 둘, 서비스 하나 / 둘)은 새 ADR 에서 정한다 | **결정** 2026-11-09 사용자 |
| D3 | 임베딩은 Qwen3-Embedding-0.6B 와 BGE-M3 를 우리 문서(`RetrievalEvalIT` 질문)로 비교한 뒤 고른다 → 비교 결과 **Qwen3-Embedding-0.6B** | **결정** 2026-11-09 사용자 |

## 5. 결정 대기
| # | 내용 | 추천 |
|---|---|---|
| O1 | ADR-0036(운영자 1차, 상태 "제안")을 승인할지 — 고객앱 확대(D1)와 함께 새 ADR 로 | 새 ADR 하나로 묶어 승인 |
| O2 | 운영자 문서 목록(`manifest.yml`) 변경을 누가 승인하나(지금은 커밋하면 색인됨) | C1 과 같은 절차 |
| O3 | 실제 운영 매뉴얼·SOP 를 만들어 넣을지(지금은 설계 문서뿐, 답이 "설계안" 표시가 많음) | 쓸 사람이 정해질 때까지 설계 문서로 |
| C1 | 고객 공개 정책 문서: 무엇을, 누가 쓰고 누가 승인하나(지금 없음. 업무 원문을 그대로 고객 공개로 넣지 않는다) | **결정** 2026-11-09 사용자: 고객 공개 문서는 Claude 가 초안을 쓰고 사용자가 승인한다 |
| C2 | 비회원(GUEST 토큰)도 물을 수 있나 | **결정** 2026-11-09 사용자: 비회원 채팅 불가 — 로그인(회원) 필수 |
| C3 | 고객별 요청 제한 수치 | **결정** 2026-11-09 사용자(Claude 추천): 고객마다 **1분 5개·하루 50개**(Asia/Seoul 자정). 같은 키 재전송은 세지 않음. 넘으면 429 `RATE_LIMITED` + Retry-After |
| C4 | 근거 없음(NO_GROUNDING)일 때 고객에게 보일 안내 문구·연결(고객센터 등) | **결정** 2026-11-09 사용자: 근거 없을 때 = **고객앱 "고객센터 문의" 페이지**로 연결(case-service CS 접수 API + 클라이언트 handoff). 고객 문구는 Claude 가 정함(`docs/business/customer-faq/README.md`) |
| C5 | 고객 질문 원문 저장 여부·보존 기간(개인정보일 수 있음) — ADR-0036 대기 3 과 같이 | **결정** 2026-11-09 사용자(Claude 추천): 질문 원문은 **저장하지 않는다**(해시만, 설정으로도 켤 수 없음 — 질문 목록 API 없음). 답변 보존 기간은 아직 미정(지우지 않음) |
| C6 | 실시간 질문(내 주문·배송): 지금처럼 거절 + 화면 안내, 나중 Tool Calling 여부 | **결정** 2026-11-09 사용자: 고객 RAG 가 order-service `GET /orders`(고객 토큰)로 최근 주문 목록을 조회해 "주문 건 문의"에 연결. 앞의 "1차는 거절 + 안내" 대체 |
| C9 | 상담 만족도 조사 | **결정** 2026-11-09 사용자: 두지 않는다 |
| C8 | 고객용 생성 모델(운영자와 같은지) — D2 와 같이 | 기본값으로 시작: 운영자와 같은 Ollama qwen3:4b·qwen3-embedding:0.6b(ADR-0051 추천). 고객 평가셋 뒤 다시 본다 |
| D2 | Ollama 도입 여부·둘 PC(GPU·메모리 확인) | 확인 뒤 결정 |
| — | ADR-0036 기존 대기: 외부 모델·외부 전송 범위, LAN pgvector 위치, 답변 보존·질문 원문 저장, 운영자 역할 세분 | — |
