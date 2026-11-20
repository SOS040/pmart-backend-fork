# AI 상담(운영자·고객) 답변 상태 값 추가 — PRODUCT_INFO · PRODUCT_TEMPLATE (2026-11-20)

보낸 영역: rag(PC 4)
받는 앱: backoffice(AI 질문 화면 `/assistant`), customer-app(고객센터 AI 상담 채팅)

## 바뀌는 것
- 상품의 가격·판매 여부를 묻는 질문("두부 있어요?", "생수 얼마예요?")에 상품 검색 결과를 정해진 문장으로 답한다(ADR-0083).
- 응답 `state` 에 **`PRODUCT_INFO`**, `answerMode` 에 **`PRODUCT_TEMPLATE`** 가 새로 나온다.
  - 운영자 `POST/GET /assistant/policy-questions…` (계약 `ai-assistant-service.yaml` 1.2.0), 질문 목록의 상태 값도 같다.
  - 고객 `POST/GET /customer-assistant/questions…` (계약 `customer-assistant-service.yaml` 0.3.0).
- `answer` 는 여러 줄 문장이다(상품마다 "- 이름 · 가격 · 판매 상태" 한 줄). `citations` 는 빈 배열, 고객 `handoff` 는 없음(null).
- 상품 목록을 따로 담은 필드는 없다(문장만).

## 앱이 할 일
1. 모르는 `state`·`answerMode` 값을 받아도 오류로 처리하지 말 것(enum 파싱이 엄격하면 새 값 추가). 표시는 `ANSWERED` 와 같이 답 문장을 그대로 보이면 된다.
2. 줄바꿈(`\n`)을 그대로 보일 것.
3. 근거 문서 영역은 비어 있으므로 숨기거나 "근거 문서 없음" 표시.

## 서버 반영
- 설정 `pmart.assistant.product-search.base-url` 이 들어가기 전까지는 이 값이 나오지 않는다(지금처럼 문서로 답함). LAN 반영은 PC 1 배포 때.
