# RAG 계획·가공 문서

[`../references/`](../references/README.md) 의 원문과 현재 구현(`services/ai-assistant-service`)을 대조해 만든 정리·계획·새 그림을 둔다.

- 원문을 옮겨 적지 않는다. 원문의 어느 부분을 받아들이고 버리는지, 그 이유를 쓴다.
- 결정이 필요한 것은 "결정 대기"로 적고, 사용자가 정하면 날짜와 함께 "결정"으로 바꾼다. 확정된 설계 결정은 ADR 로 옮긴다.
- 파일 이름: `YYYY-MM-DD-<주제>.md`(그림은 같은 이름 `.svg`).

| 파일 | 내용 | 상태 |
|---|---|---|
| [`2026-11-07-references-review.md`](2026-11-07-references-review.md) | 11-07 원문 3개 검토, 현재 구현 대조, 결정 대기 목록 | 작성 |
| [`2026-11-07-report-operator-rag.md`](2026-11-07-report-operator-rag.md) | 운영자용 RAG(백오피스) 구성 보고서 — 구현 상태·흐름·권한·기준선·결정 대기 | 작성 |
| [`2026-11-07-report-customer-rag.md`](2026-11-07-report-customer-rag.md) | 고객용 RAG(고객앱) 구성 보고서 — 계획, 운영자용과 같은 것/새로 만들 것, 결정 대기 C1~C8 | 작성 |
| [`2026-11-07-rag-architecture-backoffice.svg`](2026-11-07-rag-architecture-backoffice.svg) | 백오피스(운영자) 경로 — 현재 구현 기준(원본 그림 문제 1~7 수정, pgvector 만, 결정 대기는 점선). 밝은·어두운 화면 둘 다 | 작성 |
| [`2026-11-07-rag-architecture-customer.svg`](2026-11-07-rag-architecture-customer.svg) | 고객앱 경로 — 계획(운영자 경로에서 그대로 쓰는 것은 실선, 새로 만들 것·결정 대기는 점선) | 작성 |
| [`2026-11-07-rag-architecture-backoffice.en.svg`](2026-11-07-rag-architecture-backoffice.en.svg) · [`2026-11-07-rag-architecture-customer.en.svg`](2026-11-07-rag-architecture-customer.en.svg) | 위 두 그림의 영문판(같은 배치, 글만 영어. 긴 글에 맞춰 화살표 시작점만 옮김) | 작성 |
| [`2026-11-18-es-hybrid-restructure-plan.md`](2026-11-18-es-hybrid-restructure-plan.md) | RAG 구조 개편 계획 — RAG 전용 Elasticsearch(BM25 + kNN → RRF → 리랭커), 1,024토큰 청킹, pgvector 제외. 현재와 차이·단계·결정 대기 Q1~Q14 | **취소**(2026-11-19 사용자 — 개편 안 함, 기록만) |
| [`2026-11-18-es-restructure-decisions.md`](2026-11-18-es-restructure-decisions.md) | 개편 계획 Q1~Q14 사용자 결정(11-18), 덧붙인 질문의 답(캐시·토크나이저·RankingPolicy·이름·digest·PG↔ES 상태 맞추기), 새 확인 사항 2개 | **취소**(2026-11-19 사용자 — 개편 안 함, 기록만) |
| [`2026-11-27-es-hybrid-rag-proposal.md`](2026-11-27-es-hybrid-rag-proposal.md) | 사용자가 준 제안 원문 — PostgreSQL 정본 + ES(BM25 + kNN) 검색 전담 | **제안**(채택 결정 대기) |
| [`2026-11-27-es-hybrid-rag-proposal-review.md`](2026-11-27-es-hybrid-rag-proposal-review.md) | 위 제안 리뷰 — 고칠 사실 3, 빠진 설계 6(근거 없음 판정 기준, **답 가로채기 단계** 등), 가로채기 먼저·병렬 비교는 결정 대기 | 작성 |
| [`2026-11-27-quick-commerce-search-review.md`](2026-11-27-quick-commerce-search-review.md) | 「퀵커머스 직원·고객 검색 아키텍처」(PDF, 원본 안 둠) 리뷰 — 종료 지점 ①ES ②벡터 ③생성, 지금 구조와 비교, 보완 A~G, 넣을 조각 5 | **채택**(사용자 2026-11-27) |
| [`2026-11-27-es-first-hybrid-plan.md`](2026-11-27-es-first-hybrid-plan.md) | ES-First 하이브리드 RAG 계획 — 적재(ES 선 색인 → 임베딩 → pgvector, 폐기 전파), 검색 종료 지점 ①ES ②벡터 ③생성, 부하·시맨틱 캐시·추론 깊이, 단계 P0~P5 | **계획**(방향: PDF 리뷰 채택 11-27, 단계 결정 대기) |

그림을 고치면 영문판(`.en.svg`)도 같은 커밋에서 고친다.
