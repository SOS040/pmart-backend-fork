# 업무 도우미 질문 지우기 = 채팅 지우기 (2026-11-27)

보낸 영역: rag(PC 4)
받는 앱: backoffice(`/assistant`)
근거: 요청서 `docs/architecture/service-requests/ai-assistant-service.md` AI-1, ADR-0084, 계약 `contracts/openapi/ai-assistant-service.yaml` 1.3.0(변경 없음)

## 결론
- 2026-11-19 회신(`2026-11-19-reply-assistant-store-question.md`)에서 말한 **질문 하나 지우기 API(`DELETE /assistant/question-history/{answerId}`)는 만들지 않는다**(사용자 2026-11-27).
- 개인정보가 섞인 질문을 지우려면 **그 질문이 든 채팅을 지운다**: `DELETE /assistant/chat-sessions/{sessionId}`(Idempotency-Key 필요) — 그 채팅의 질문·답변을 모두 지우고, 되돌릴 수 없다. 없거나 이미 지웠어도 204.
- 채팅 기능 전의 옛 질문도 모두 채팅에 묶여 있어 같은 방법으로 지울 수 있다. `PolicyAnswer.sessionId` 로 어느 채팅인지 안다.
- 지우지 않아도 **마지막 질문 뒤 14일**이 지나면 서버가 채팅째 지운다.

## 화면에 부탁
- "이 질문 지우기"를 따로 두지 말고, 채팅 지우기 버튼에 "이 채팅의 질문과 답변이 모두 지워집니다"를 확인받는다.
- 화면 안내 문구 예: "채팅은 마지막 질문 뒤 14일이 지나면 자동으로 지워져요."
