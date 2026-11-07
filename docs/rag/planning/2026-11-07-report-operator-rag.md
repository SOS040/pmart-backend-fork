# 운영자용 RAG (백오피스) 구성 보고서

- 작성: 2026-11-07
- 상태: **구현 완료(1차 기준선) · LAN 미배포 · 외부 모델 없음**
- 그림: [`2026-11-07-rag-architecture-backoffice.svg`](2026-11-07-rag-architecture-backoffice.svg) (영문 [`.en.svg`](2026-11-07-rag-architecture-backoffice.en.svg))
- 정본: ADR-0036(상태 "제안"), `services/ai-assistant-service/CLAUDE.md`, 계약 `contracts/openapi/ai-assistant-service.yaml`(1.1.0)

## 1. 요약
본사·센터 운영자가 정책·업무 절차를 물으면, 저장소 문서를 검색해 **근거(출처·절·지위)와 함께** 답한다. 근거가 없으면 지어내지 않고 `NO_GROUNDING` 으로 답한다. 실시간 데이터 조회와 명령 실행은 하지 않는다. 지금은 해시 임베딩과 문장 발췌만 쓰는 기준선이며, 생성 모델·의미 임베딩은 결정 대기다.

## 2. 범위
| 한다 | 하지 않는다 |
|---|---|
| 정책·업무 절차 질의응답, 근거 표시 | 특정 주문·재고 등 현재 상태 질문 → `REALTIME_DATA_REQUIRED` |
| 내 질문 목록·답변 다시 보기 | 실행 요청("취소해 줘") → `OUT_OF_SCOPE` |
| 권한 밖 문서 제외 | 다른 서비스 DB·API 호출, 이벤트 발행·구독 |

## 3. 구성요소
| 구성요소 | 내용 |
|---|---|
| 클라이언트 | 백오피스 웹(운영자). 고객앱은 [고객용 보고서](2026-11-07-report-customer-rag.md) |
| API Gateway | 운영자 JWT 검증, `/api/v1/assistant/**` → ai-assistant-service |
| ai-assistant-service | Spring Boot + Spring AI 2.0.2, 로컬 포트 8508. `api`(AssistantController) · `application`(PolicyQuestionService·Retriever·AnswerGenerator·KnowledgeJobs) · `domain`(분류·순위·인용 검사) · `infrastructure`(JDBC 저장·임베딩·적재) |
| RAG 전용 DB | PostgreSQL + pgvector(+ `pg_trgm`) 1개. 표: `index_snapshot`·`index_source_file`·`doc_chunk`·`embedding_cache`·`policy_answer`. LAN 에는 아직 없음(위치 결정 대기, 설정 자리 `postgres-pc4:15433`) |
| 임베딩 | 지금 `LocalHashEmbeddingModel`(글자 2~3그램 해시, 384차원). 모델 정보는 스냅숏에 기록(EmbeddingSpec) |
| 답변 생성 | 지금 `ExtractiveAnswerGenerator`(근거 문장 발췌). `GenerativeAnswerGenerator`(Spring AI ChatModel)는 코드만 있고 모델 결정 대기, 시험은 `fake` |

## 4. API
| 메서드·경로 | 내용 |
|---|---|
| `POST /assistant/policy-questions` | 질문(`Idempotency-Key` 필수). 같은 키 재전송은 저장 결과·진행 상태 |
| `GET /assistant/policy-questions/{answerId}` | 답변 하나 |
| `GET /assistant/question-history` | 내 질문 목록(BR-46, 커서 쪽 넘김) |

결과 상태: `ANSWERED` · `NO_GROUNDING` · `REALTIME_DATA_REQUIRED` · `OUT_OF_SCOPE` · `FORBIDDEN_SCOPE`.

## 5. 처리 순서 (`PolicyQuestionService.ask`)
1. **선점** — 요청 해시로 답변 기록을 `PROCESSING` 으로 만들고 lease 를 잡는다. 같은 요청은 한 번만 처리.
2. **의도 분류** — 규칙 기반(모델 없음). 실시간·실행 요청은 여기서 끝낸다.
3. **스냅숏 고정** — 활성 색인 스냅숏 하나를 고정(공유 잠금). 처리 중 색인이 바뀌어도 섞이지 않는다.
4. **검색** — 3절과 같이.
5. **생성** — 근거 D1..Dn 로 답을 만든다(지금은 발췌).
6. **인용 검사** — 답의 인용이 검색 결과와 맞는지. 아니면 `NO_GROUNDING`.
7. **완료 저장** — `attempt`·`PROCESSING` 이 맞을 때만. lease 를 잃었으면 자기 결과를 버린다.

검색·생성(④⑤)은 DB 트랜잭션 밖에서, 제한 시간 안에 실행한다.

## 6. 검색
- 스냅숏의 임베딩 모델·버전·차원이 지금 모델과 다르면 검색하지 않는다.
- SQL 한 번: **WHERE 접근 범위·생애(현행 문서) 먼저** → 벡터 0.6 + 키워드(`pg_trgm`) 0.4 → 후보 40 → 상위 5, 점수 0.18 미만 제외.
- 결과 청크마다 문서 경로·절·지위(확정 정책·채택 설계·설계안 등)·접근 범위.

## 7. 권한·보안
- 권한 밖 문서는 SQL 에서 빼고 가져온 뒤 거르지 않는다.
- 저장된 답변을 돌려줄 때마다 지금 운영자인지·본인인지·인용 근거 권한이 있는지 다시 본다(`stored(...)`).
- 질문·문서 본문·토큰은 로그에 남기지 않는다(답변 ID·상태·소요 시간·청크 수만).
- 외부 모델 제공자 모듈은 넣지 않았다(외부 전송 범위 결정 전).

## 8. 문서 적재
- 대상: `knowledge/manifest.yml` 의 명시 목록 — 업무 명세 8, 사가 흐름 4, 상태표, overview·domain-map·glossary·service-map, ADR 전부. worklog·handoff·코드·비밀은 제외.
- 지위·생애: 문단 태그와 ADR 상태 줄로 판정, 승인된 대체 ADR 의 명시 결정만 SUPERSEDED.
- 실행: `ingestKnowledge`(수동, 색인 잠금) → 새 스냅숏 BUILDING → ACTIVE 전환, 이전 것 RETIRED(정리는 `pruneSnapshots` 수동).

## 9. 품질 기준선 (2026-11-05, `RetrievalEvalIT`)
| 기준 | recall@5 | MRR |
|---|---|---|
| 문서 경로 | 0.76 | 0.628 |
| 절 | 0.60 | 0.387 |

해시 임베딩 기준이다. 의미 검색·생성 품질은 아직 측정하지 않았다.

## 10. 한계
- 넣은 문서가 대부분 **시스템 설계 문서**(업무 명세는 대부분 "설계안")라, 실제 운영 매뉴얼·SOP 가 없다. 답에 "설계안" 표시가 많다.
- 해시 임베딩이라 같은 뜻 다른 표현에 약하다.
- LAN 미배포라 운영자가 실제로 쓸 수 없다.

## 11. 결정 대기
| # | 내용 |
|---|---|
| O1 | ADR-0036 승인 — 고객용 확대와 함께 새 ADR 로 |
| O2 | 문서 목록(`manifest.yml`) 변경 승인 주체 |
| O3 | 실제 운영 매뉴얼·SOP 를 만들어 넣을지 |
| D2 | 추론 서버(Ollama 등)·생성 모델·둘 PC |
| D3 | 임베딩 모델(Qwen3-Embedding-0.6B vs BGE-M3) 비교 평가 |
| — | LAN pgvector 위치, 답변 보존 기간·질문 원문 저장, 운영자 역할 세분 |

## 12. 다음 단계
1. O1·D2·D3·pgvector 위치 결정
2. 추론 서버 준비(사전 준비 목록은 별도) → 임베딩 비교 평가 → 1024차원 마이그레이션·재적재
3. 생성 모델 연결·답변 품질 평가 → LAN 배포
