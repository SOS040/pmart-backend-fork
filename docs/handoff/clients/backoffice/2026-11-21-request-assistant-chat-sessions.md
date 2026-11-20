# AI 질문 화면을 채팅 세션으로 — 여러 번 질문, 채팅 삭제, 14일 보존 (2026-11-21)

보낸 영역: rag(PC 4)
받는 앱: backoffice(`/assistant`)
근거: ADR-0084, 계약 `contracts/openapi/ai-assistant-service.yaml` 1.3.0

## 바뀌는 것 (서버)
- 질문은 **채팅 세션**에 속한다. `POST /assistant/policy-questions` 본문에 `sessionId`(선택)를 넣으면 그 채팅에 이어 묻고, 빼면 새 채팅이 생긴다. 응답 `PolicyAnswer` 에 `sessionId` 와 `question`(원문 저장 설정일 때) 이 추가됐다.
- 같은 채팅의 앞 대화를 참고해 "그럼 반품은?" 같은 이어지는 질문을 이해한다(답의 근거는 여전히 문서).
- 새 API
  - `GET /assistant/chat-sessions?size=&cursor=` — 내 채팅 목록(마지막 질문 최근 순). 항목: `sessionId`, `title`(첫 질문, 없으면 첫 답변 앞부분, 둘 다 없으면 null), `questionCount`, `createdAt`, `lastAskedAt`.
  - `GET /assistant/chat-sessions/{sessionId}` — 채팅 하나. `messages` = 질문 순 `PolicyAnswer` 배열(처리 중이면 state `PROCESSING`). 없거나 남의 채팅이면 404.
  - `DELETE /assistant/chat-sessions/{sessionId}` — 내 채팅 삭제(질문·답변째, 되돌릴 수 없음). **`Idempotency-Key`·`X-Source-System` 헤더 필요.** 없거나 이미 지웠어도 204.
- 마지막 질문 뒤 14일이 지난 채팅은 서버가 지운다(설정 반영 뒤). 지금까지의 질문은 질문 하나가 채팅 하나로 옮겨진다.

## 앱이 할 일 (제안 — 화면 모양은 앱 판단)
1. 왼쪽 목록을 `GET /assistant/chat-sessions` 로(지금의 질문 목록 대신). "새 채팅" 버튼 = 다음 질문을 `sessionId` 없이 보냄.
2. 채팅을 고르면 `GET /assistant/chat-sessions/{sessionId}` 의 `messages` 를 대화처럼(질문·답변 차례로) 보이고, 입력창의 질문은 그 `sessionId` 로 보낸다.
3. 채팅마다 삭제 버튼(확인 대화 상자 — "지우면 되돌릴 수 없어요") → `DELETE …` 204 면 목록에서 빼고, 보고 있던 채팅이면 새 채팅 화면으로.
4. 보존 안내 한 줄: "채팅은 마지막 질문 뒤 14일이 지나면 자동으로 지워져요."
5. 질문 원문은 서버가 돌려준다(`question`) — 탭 메모리에 따로 기억하던 질문(askedQuestions)은 서버 값이 없을 때만.
6. 기존 `GET /assistant/question-history` 는 당분간 그대로 동작한다.
