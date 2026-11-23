# customer-assistant-service 요청 (받는 영역 rag — PC 4)

## CA-1. 상품 영양·알레르기 질문을 정해진 문장으로 (2026-11-23, 보낸 영역 platform — PC 1)

- **지금**: ADR-0083 — 상품 질문(이름 + 가격·판매 여부)은 search-service `GET /search/products` 상위 3개를 상품명·기본가·판매 상태로 정해진 문장. 영양·알레르기 질문은 상품 질문이 아니라 문서로 가거나 답을 못 한다.
- **원천(2026-11-23 LAN 반영)**: search 0.11.0(products-v9) 결과 항목 `nutrition`(100g·100mL 기준 `basisUnit` G·ML, `energyKcal`·`sodiumMg`·`sugarsG`·`proteinG`·`fatG`, 모르는 칸 null)·`allergens`(`contains` 함유·`mayContain` 같은 시설 제조·`text` 원문, 법정 이름). 영양 필터 `filters=nutrition.저당|저나트륨|고단백`(같은 키 AND). LAN 504개 중 영양 239·알레르기 300. 계약 `contracts/openapi/search-service.yaml` 0.11.0.
- **필요한 것**(ADR-0083 결정 2·3 을 넓힘 — 새 ADR 또는 0083 갱신은 PC 4):
  1. 질문 판별(규칙, 모델 없음): 상품 이름 + 영양·알레르기 말(칼로리·열량·당·당류·나트륨·단백질·지방·알레르기·알러지·땅콩 등 성분 + 들어있어/함유/있어). "저당·저나트륨·고단백 ○○" 은 필터 질문.
  2. 정해진 문장(모델이 숫자·성분을 만들지 않는다):
     - 영양: "{상품명}은(는) {100g|100mL} 당 열량 N kcal, 당류 N g …" — 있는 칸만. 모든 칸이 null 이면 "표시사항 영양정보가 아직 없어요. 상품 상세의 표시사항을 확인해 주세요."
     - 알레르기: 함유면 "{성분}이(가) 들어 있어요", 같은 시설이면 "{성분}을(를) 쓰는 제품과 같은 시설에서 만들어 섞일 수 있어요". **`allergens` 가 null 이거나 그 성분이 목록에 없으면 "없다"고 답하지 않는다** — "표시사항에서 확인하지 못했어요. 알레르기가 있으면 상품 표시사항을 꼭 확인해 주세요."(목록에 없음 ≠ 들어 있지 않음).
     - 필터 질문: `filters=nutrition.<값>` 으로 찾아 상위 3개 + 기준 한 줄(저당 = 당류 100g 당 5g·100mL 당 2.5g 미만, 저나트륨 = 120mg 미만, 고단백 = 100g 당 11g·100mL 당 5.5g 이상).
  3. 의료·건강 조언은 하지 않는다(얼마나 먹어도 되는지 등 → 문서·상담 연결).
- **사용자**: 2026-11-23 "다 하면 될 것 같은데"(검색 개선 남은 항목 순서 — 1번 이 요청).
- **고객 쪽 차이**: 센터를 주지 않는다(ADR-0083 그대로, "주소에 따라 받아 볼 수 없는 상품도 있어요"). 알레르기 답 끝에 늘 "알레르기가 있으면 표시사항을 꼭 확인해 주세요".
- **상태**: 요청 — PC 4 구현 차례. 운영자 쪽 AI-2(`ai-assistant-service.md`). 검색 쪽에서 지킬 것(정정 검색어·0건 비슷한 상품은 찾은 상품 아님·알레르기 목록에 없음 ≠ 없음·센터 없음·할인 끝 시각·금칙어는 검색 전에): `docs/architecture/product-search.md` §13(2026-11-23).

## CA-2. AI 상담 채팅 금칙어 — 욕설은 막고 정지 단어는 정지 사건 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "A는 문의나 채팅에 입력 자체가 안 되게 해주고, C는 걸리면 정지되는 걸로 해줘. B도 해줘" — B 는 A 처럼 막기, 막기는 정지 없음, 범위 "문의 + AI 채팅 + 라이더 문의 대화")
- **같은 규칙**(case 0.37.0 과 같게): 고객이 보낸 채팅 글에
  - **막기 단어**(욕설 — case `pmart.cases.blocked-words` 와 같은 목록)가 있으면 **받지 않는다**(답하지 않고 "쓸 수 없는 말이 들어 있어요" 안내, 정지 없음). 글은 저장·색인·로그하지 않는다.
  - **정지 단어**(위협·거친 말 — `pmart.cases.profanity-words` 와 같은 목록)가 있으면 사건 `CustomerProfanityDetected`(source **AI_CHAT** — case 0.37.0 에서 값만 더함, 스키마 `contracts/events/CustomerProfanityDetected.v1.json`) — customer(CU-7)가 7일 정지·알림.
- 대조 방식: 소문자로 바꾸고 띄어쓰기·문장부호를 지운 뒤 포함 비교(case `domain/ProfanityRules` 와 같게 — 코드 복제 대신 같은 규칙으로 구현). 목록 정본은 pmart-config(사용자 승인 뒤 PC 1 반영) — 서비스마다 키를 둘지 공통으로 둘지 PC 1·PC 4 가 정해 주세요.
- 검색과의 순서: 금칙어 검사를 상품 검색·문서 검색·모델 호출보다 먼저 — `docs/architecture/product-search.md` §13-3.
- **상태(2026-11-23 PC 4)**: **막기 구현**(customer-assistant 0.5.0) — 질문에 막기 단어가 있으면 422 `BLOCKED_WORDS`("쓸 수 없는 말이 들어 있어요. 고쳐서 다시 보내 주세요."), 멱등 기록·요청 제한보다 먼저 거절해 질문·요청 해시를 남기지 않는다. 키는 delivery-execution 처럼 **서비스 키** `pmart.assistant.customer.blocked-words`(제안 — 값은 case 와 같은 목록, PC 1 이 pmart-config 에 넣을 때까지 비어서 꺼짐). 고객앱은 422 `BLOCKED_WORDS` 의 detail 을 그대로 보여 주면 된다.
  - **정지 단어는 아직**(사용자 2026-11-23 "이번엔 막기만 PR"): customer-assistant 는 사건을 내는 기능(common-messaging·outbox)이 없고, `CustomerProfanityDetected` 스키마는 `aggregateType` 이 `Case` 고정·`inquiryId`·`caseId` 필수라 AI 채팅에 맞지 않으며, customer 는 `platform.case.v1` 만 구독한다. 정할 것(PC 1): ① 토픽(이 서비스 자체 토픽 + customer 바인딩 추가 / 다른 길) ② 스키마(AI_CHAT 일 때 inquiryId·caseId 대신 answerId, aggregateType) — 정해지면 PC 4 가 다음 PR.
- **보류(2026-11-23 사용자 "채팅 막기와 정지 단어 일단 보류할 거야. 이미 구현했어도 본 서비스와 연결하지 말고 md 파일로 기록만")**: 막기·정지 모두 보류. **사실 정정**: PC 4 PR #384(customer-assistant 0.5.0 — 질문에 막기 단어 422 BLOCKED_WORDS, 기록·요청 제한·검색·모델 전에 거절, 질문·해시 저장 안 함, check 통과 test 141·IT 31)는 보류 지시 **전에** PR 세션이 병합(8a7c320c)·pmart-config `customer-assistant-service-lan.yml` `blocked-words`(7d8e72c)·LAN 배포까지 했다(2026-11-23 20:20). 지금 LAN 은 막기가 **켜져 있다**. 보류대로 연결을 끊으려면 pmart-config 의 `blocked-words` 를 빼고 customer-assistant 재시작(키가 없으면 꺼짐 — 코드는 main 에 남아도 동작 안 함) — 사용자 결정 대기. 정지 단어(AI_CHAT)는 결정 전 — PC 1 추천안(사용자 결정 안 함): ① customer-assistant 자기 토픽 `platform.assistant.v1`(outbox) + customer 바인딩 추가(자동 7일 정지 처리는 CU-7 그대로), ② `CustomerProfanityDetected` 스키마에서 inquiryId·caseId 를 source INQUIRY 일 때만 필수, AI_CHAT 은 답변 기록 ID — aggregateType Case 고정도 풀기. customer 는 지금 platform.case.v1 만 구독하므로 AI 채팅 사건이 와도 정지되지 않는다. 다시 시작하면 #384 를 main 위로 다시 맞춰 검토.

## CA-3. FAQ 의견 집계 줄에 분류 이름 `categoryTitle` (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "지금 안하면 언제 할건데?")
- **왜**: 통합 백오피스 FAQ 의견 집계 화면(BR-91, 프론트 #419·#421)이 분류 이름을 받아 올 곳이 없다 — 고객용 FAQ 목록 API 는 고객 토큰만이라 직원 화면이 부를 수 없어, 지금은 13개 이름을 화면에 박아 두었다. 분류는 안내 문서(`docs/business/customer-faq/NN-<키>.md`)가 늘면 같이 는다.
- **요청**: `GET /customer-assistant/faq-feedback` 줄에 `categoryTitle`(FaqItem·FaqCategory 와 같은 값 — 안내 문서 제목). 지금 FAQ 에 없는 줄(question null)은 null 이어도 됨. 추가 칸만.
- 받는 화면: 통합백오피스 프론트 — 오면 박아 둔 이름 대신 이 값을 쓴다.
