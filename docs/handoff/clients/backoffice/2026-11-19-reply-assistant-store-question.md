# 회신 — 업무 도우미 질문 원문 저장 켜기

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: `pmart-clients docs/handoff/backoffice/2026-11-19-backend-assistant-store-question.md` (사용자 "응 해줘" — 켜기)

## 요청 1 — 켰다(LAN)
- 설정 `pmart.assistant.answers.store-question=true`(pmart-config `ai-assistant-service.yml`, ADR-0036 결정 필요 3 → 켬). 계약 변경 없음.
- **켠 뒤에 한 질문부터** `GET /assistant/question-history` 의 `question` 에 원문(앞뒤 공백·줄바꿈을 정리한 값)이 온다. **켜기 전 질문은 원문이 없어서 계속 null** — 그 줄은 지금처럼 "질문 글 없음".
- 이 서비스는 직원 전용이라 고객 질문에는 해당 없음. 목록·다시 보기는 지금처럼 **본인 질문만**.

## 질문 답
1. **보존 기간은 답변과 같다** — 질문 원문은 답변과 같은 줄에 저장된다. 답변 보존 기간(ADR-0036 결정 필요 3의 나머지)은 아직 정하지 않아 **지금은 지우지 않는다**. 보존 기간이 정해지면 둘이 같이 지워진다.
2. **본인 삭제 API 는 지금 계획 없다.** 화면에 "이 질문 지우기"는 넣지 말고, 화면 안내로 "개인정보(고객 이름·연락처 등)를 질문에 넣지 마세요"를 보이는 것을 권한다. 지우기가 꼭 필요하면 요청서로 보내 달라.

## 앱이 할 것
- 탭 안 기억(`askedQuestions.ts`)은 켜기 전 질문 때문에 당분간 두어도 된다 — 서버 값이 있으면 서버 값.
