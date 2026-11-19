# ADR-0073 검색 동의어(백오피스 관리·승인·즉시 반영)와 오타 교정(자모 기준, 상황별 3단계)

- 상태: **승인** (2026-11-20, 사용자 "개발 규모 안 따진다면 어떤걸 추천해" → "그럼 그렇게 해줘", 승인자 역할 "MD 요청, 관리자 승인")
- 관련: ADR-0031(색인은 Logstash, 검색 서비스는 조회 + 자기 색인만 씀), ADR-0021(DB 없는 소비자의 버전 비교 멱등), ADR-0072(products-v6), 계획 `docs/operations/2026-11-19-search-next-plan.md` 7번

## 배경
- 지금은 동의어가 없고(계란↔달걀을 따로 찾아야 함) 오타("두뷰")는 결과 0건. 검색 기록 색인(search-log)에 결과 수가 남아 0건 검색어를 셀 수 있다.
- Elasticsearch 8.18 은 **동의어 집합(Synonyms API)** 을 색인 밖에 두고 **검색 분석기**(synonym_graph, updateable)로 쓸 수 있다 — 목록을 바꿔도 재색인 없이 반영된다.

## 결정
1. **주인: search-service**(서비스 지도상 "키워드·자동완성" 담당). DB 는 두지 않는다 — 동의어 규칙의 정본은 search-service 가 쓰는 색인 `search-synonym-rules`(규칙·버전·승인 참조·변경 기록), 실제 적용은 ES 동의어 집합 `pmart-product-synonyms`(버전 비교로 멱등 — ADR-0021).
2. **동의어 반영**
   - 상품 색인 `products-v7`: 이름 필드의 **검색 분석기**에 `synonym_graph`(synonyms_set = pmart-product-synonyms, updateable). 색인 분석기는 그대로(색인 크기·순위 변화 최소).
   - 규칙 형식: 같은 뜻 묶음(`계란, 달걀`) 또는 한 방향(`콜라 => 코카콜라, 펩시`). 낱말 수·규칙 수 상한은 설정.
3. **관리·승인**
   - 3-1 백오피스 화면(백오피스 웹 — PC 2): 규칙 목록·추가·수정·삭제 초안 → 승인 요청. "결과 0건 검색어"(최근 N일, 많이 나온 순, 숨김 목록 제외 — search-log) 화면에서 바로 동의어 초안을 만들 수 있다.
   - 3-2 승인: approval-service 새 유형 **`SEARCH_SYNONYM`**(본문 = 바뀐 규칙 묶음 + 기준 버전). `ApprovalDecided`(APPROVED) → search-service 가 기준 버전이 같을 때만 반영(다르면 REJECTED — 그 사이 다른 변경) → 동의어 집합 갱신 + 규칙 색인 + 승인 회신(`ApplicationReceiptRecorded`, ADR-0039 RC 규칙).
   - 3-3 **승인자 역할(사용자 결정 2026-11-20 "MD 요청, 관리자 승인")**: 요청자 = 상품·전시 담당(MD)·OPERATOR_ADMIN, 승인자 = OPERATOR_ADMIN, 자기 승인 금지 — 기존 DISPLAY 와 같은 줄.
4. **오타 교정**(자모 기준 — 한글은 자모로 나눠야 "두뷰"↔"두부"가 한 글자 차이)
   - search-service 가 상품명 낱말 사전 색인 `search-terms`(낱말·자모·상품 수)를 주기적으로(설정, 예 10분) 상품 색인에서 다시 만든다(nori 로 낱말 나누기) — 검색 서비스가 쓰는 자기 색인.
   - **결과 0건** → 검색어 낱말 중 사전에 없는 것을 자모 거리(fuzzy, 낱말 길이에 따라 1~2)로 가장 가깝고 상품 수가 많은 낱말로 바꿔 **다시 찾고**, 응답 `correctedQuery`(+ 원래 `query`). 앱: "'두부' 검색 결과예요 · '두뷰'로 찾기"(`?q=두뷰&noCorrection=true`).
   - **결과 적음**(설정, 제안 3건 이하) → 결과는 그대로, 응답 `suggestedQuery`. 앱: "혹시 '두부'를 찾으셨나요?"
   - **자동완성** → 일치 후보가 없으면 교정 후보를 `suggestions` 뒤에.
   - 고친 낱말은 **사전(실제 상품명)에 있는 낱말만**. 초성 검색·숫자·영문 모델명은 고치지 않는다.
5. **색인**: products-v7 = v6 + 이름 검색 분석기(동의어). 재색인 1회(ADR-0072 와 같은 절차, 무중단). search-terms·search-synonym-rules 는 search-service 가 만든다.

## 단계
| 단계 | 무엇 | 누가 |
|---|---|---|
| A | products-v7(동의어 검색 분석기)·동의어 집합 반영 API(내부)·규칙 색인·오타 사전·교정(0건/적음/자동완성)·0건 검색어 집계 API·계약 search 0.4.0 | 백엔드(PC 1) |
| B | 승인 유형 SEARCH_SYNONYM·초안·승인 요청·ApprovalDecided 반영·회신 | 백엔드(PC 1, approval·search) — 승인자 역할 결정 뒤 |
| C | 백오피스 화면: 동의어 관리·0건 검색어 | 백오피스 웹(PC 2) 요청서 |
| D | 고객 앱: correctedQuery·suggestedQuery 문구 | 고객 앱 요청서 |
| E | 처음 동의어 목록 초안(상품 약 500개 기준) → 사용자 검토 → 승인으로 넣기 | 백엔드 초안·사용자 |

## 결과(예상)
- 동의어는 승인 즉시 반영(재색인 없음). 오타는 사전이 상품명을 따라 저절로 갱신.
- 0건 검색어가 운영 화면에 보여 동의어·상품명 개선의 근거가 된다.

## 진행
- 2026-11-20 단계 A 구현·시험 통과(search 단위 22·통합 15) — products-v7·동의어 집합·오타 사전·교정·0건 검색어 API, 계약 search 0.4.0. **LAN 배포 전**(배포 때: pmart-config `pmart.search.correction.few-results: 3`, products-v7 재색인, es-init 동의어 집합). 시험에서 잡은 것: 오타 사전 bulk 본문 charset 누락으로 한글이 깨짐, nori 가 "순두부" 를 "순·두부" 로 나눠 사전에 원래 낱말도 넣음.
- 2026-11-20 단계 B 구현·시험 통과(approval 단위 31·통합 14, search 단위 26·통합 16) — 계약 approval 0.5.0·search 0.5.0. **LAN 배포 전.**
  - 요청: 백오피스가 `GET /search/synonym-rules` 의 version 을 기준 버전으로 approval-service 변경 요청(`SEARCH_SYNONYM`, targetRef `pmart-product-synonyms`, 행 = 규칙 ID·`op` UPSERT/DELETE·`terms`) → 제출. 요청 역할은 새 설정 `requester-roles`(MD·OPERATOR_ADMIN), 승인 OPERATOR_ADMIN, 자기 승인 금지. 대상이 하나라 심사 중인 동의어 승인은 한 번에 하나.
  - 반영: search-service 가 `ApprovalDecided` 를 받아 기준 버전 = 지금 버전이고 모든 행이 맞을 때만 전부 반영(아니면 STALE_VERSION·LINE_REJECTED 로 아무것도 안 바꿈) → 동의어 집합 → 규칙 색인 state → 반영 기록 → 회신. DB 가 없어 inbox·outbox 대신 승인 ID·버전으로 멱등, 회신은 Kafka 로 바로(실패하면 사건을 다시 받아 같은 회신). 반려 결정은 회신 없음.
  - 한계: 재시도를 다 쓴 사건은 `platform.approval.v1.DLT` 로만 가고(dead_letter 기록·재처리 API 없음) — 그때는 다시 승인 요청. 소비는 한 인스턴스 전제.
  - 배포 때(사용자 허락 뒤): pmart-config(search 바인딩 `approvalEvents`, approval `approver-roles`·`requester-roles`) → 시험 사본 맞춤 → search·approval·api-gateway 배포. products-v7 재색인·동의어 집합·few-results 는 단계 A 배포(2026-11-20 03:3x, PR 세션 — 사용자 "여기서 바로 배포해", cc99fe73)에서 끝남.
- 2026-11-20 단계 B LAN 배포(사용자 "응 배포해"): pmart-config 7b2b18c, 이미지 3ef008b3 — approval·search·api-gateway. 남은 것: 단계 C(백오피스 요청서)·D(고객 앱 요청서)·E(처음 동의어 목록).
- 2026-11-20 단계 D 요청서 `docs/handoff/clients/customer-app/2026-11-20-request-search-correction-text.md`(교정 문구·원래 검색어로 찾기·다음 쪽 규칙 0.5.1).
- 2026-11-20 단계 C 요청서: `docs/handoff/clients/backoffice/2026-11-20-request-search-synonym-screen.md`(동의어 관리 화면·0건 검색어에서 초안). 구현은 같은 뜻 묶음만 — 한 방향 규칙(`=>`)은 승인 형식 검사가 막는다(필요하면 새 결정). 남은 것: 단계 E(처음 동의어 목록).
