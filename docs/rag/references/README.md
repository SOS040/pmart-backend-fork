# RAG 참고 원문

사용자가 넣은 외부 자료(검토 글·추천 글·그림)의 **원본**을 날짜별로 둔다.

- 원문은 고치지 않는다. 파일 이름도 바꾸지 않는다(받은 그대로).
- 원문을 읽고 정리·계획한 것은 [`../planning/`](../planning/README.md) 에 쓴다.
- 원문의 내용은 참고 의견이다. 결정은 ADR-0036·사용자 결정이 정본이다.

| 날짜 | 파일 | 무엇 | 반영 |
|---|---|---|---|
| 2026-11-07 | [`spring ai rag arch.svg`](2026-11-07/spring%20ai%20rag%20arch.svg) | Spring AI RAG 구성도(Client → Gateway → Spring AI → VectorStore/ChatModel, 문서 적재) | [`planning/2026-11-07-references-review.md`](../planning/2026-11-07-references-review.md) §1 |
| 2026-11-07 | [`spring_ai_rag_architecture_review.md`](2026-11-07/spring_ai_rag_architecture_review.md) | 위 그림의 검토 글 | 같은 문서 §2 |
| 2026-11-07 | [`baropick_rag_free_models.md`](2026-11-07/baropick_rag_free_models.md) | 무료 임베딩·대화 모델 추천(Qwen3·BGE-M3·Ollama) | 같은 문서 §3 |

원본 SHA-256(2026-11-07 넣을 때):
- `spring ai rag arch.svg` `279316fa…19aa819460`
- `spring_ai_rag_architecture_review.md` `094b72ed…5e5392f2`
- `baropick_rag_free_models.md` `1fc73750…aff945163`
