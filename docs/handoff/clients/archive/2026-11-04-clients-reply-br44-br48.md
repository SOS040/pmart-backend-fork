# [pmart-backend → pmart-clients] 회신: BR-44·45·46·47·48 (2026-11-04)

- 대상: pmart-clients 요청서
  - `docs/handoff/clients/2026-11-04-backend-category-addition-500.md`(BR-48)
  - `…-search-not-carried.md`(BR-47)
  - `…-approval-screen-fixes.md`(BR-45)
  - `…-content-import-registration.md`(BR-44)
  - `…-ai-assistant-contract.md` §4(BR-46 남은 항목)
- 상태: **코드·계약·시험 완료, LAN 미배포.** LAN 반영 시점은 백엔드가 배포한 뒤 따로 알린다(배포 전에는 아래 동작이 LAN 에 없다).
- 바뀐 계약:
  - `search-service.yaml` 0.1.1(설명만)
  - `product-content-service.yaml` 0.1.1(필드 추가)
  - `ai-assistant-service.yaml` 1.1.0(경로 추가)
  - approval-service 계약은 그대로(응답을 계약에 맞춤)

## BR-48 카테고리 추가 500 → 원인은 큰 업로드의 메모리 부족
- **원인**: "이미 있는 카테고리" 판정이 아니었다.
  - LAN 로그 6건(11-04 15:57~18:50 KST)이 모두 `OutOfMemoryError: Cannot reserve … bytes of direct buffer memory` 였다.
  - 서버가 업로드한 zip 전체(70~220MB)를 메모리에 한 번에 읽다가 실패했다.
  - 상품 일괄 등록 500 도 같은 원인이었다.
- **수정**:
  - 업로드 파일을 메모리에 통째로 올리지 않는다(해시는 스트림으로, 묶음은 다시 열어 읽음).
  - 적용 대상: product-service(카테고리 추가·상품 일괄 등록), product-content-service(콘텐츠 일괄 등록), pricing-service(가격 일괄 등록).
- **결과**:
  - 이미 있는 카테고리만 담긴 묶음은 계약대로 **422 `NOTHING_TO_ADD`** 다. 시험에 추가했다.
  - 같은 id·다른 이름 등은 400 `CATEGORY_CONFLICT`(기존 그대로).
- 클라이언트 변경 불필요("추가할 카테고리가 없어요" 안내가 그대로 동작).
- 참고: 콘텐츠 일괄 등록은 이미지를 저장소에 올리느라 이미지를 하나씩 메모리에 읽는다(묶음 전체가 아니라). 매우 큰 묶음은 지금처럼 나눠 올리는 편이 안전하다.

## BR-47 센터 지정 검색
| # | 회신 |
|---|---|
| 1 미취급 | `MFC-SEED-01` 은 데이터 문제였다. 11-04 저녁 백엔드가 이 센터에 **275개 SKU 를 모두 취급 ON + 초기 재고 10~30개**로 넣었다. 지금 LAN 에서 `mfcId=MFC-SEED-01` → items 50, `carried: true`. `MFC-BSN-01` 은 **LAN 에 없는 센터**라 전 상품이 미취급으로 나온다. LAN 센터는 지금 `MFC-SEED-01` 하나다(주소의 담당 센터가 이 값이 되도록 권역 데이터를 맞춰야 하면 따로 요청) |
| 2 total | 정확히 세려면 색인에 센터별 취급 정보가 있어야 하고, 그러려면 재색인이 필요하다(재색인 선행 버그 2건 수정 전이라 보류). 그래서 **계약에 의미를 적었다**: `total` = 검색 조건에 맞는 색인 건수, mfcId + includeNotCarried=false 면 미취급을 빼기 전 값이라 받는 건수보다 클 수 있다. 첫 페이지에서 끝까지 다 받은 경우만 실제 건수로 맞춘다. 정확한 건수가 필요하면 `includeNotCarried=true` 로 받아 구분해 센다 |
| 3 빈 페이지 + 다음 커서 | **서버가 채운다**: 미취급을 뺀 만큼 다음 문서를 더 읽어 페이지를 채운다(최대 5번). 그래도 items 가 비거나 size 보다 적은데 `nextCursor` 가 있으면 "더 있음" — 계속 조회한다(계약 `includeNotCarried` 설명에 명시) |
| 4 정책 | 업무 문서 기준은 **숨기지 말고 구분 표시**다. Commerce 1006행 "해당 PPC가 미취급이거나 품절인 경우 이를 구분한다", 3627행 수용 기준 "검색 0건, 검색 서버 오류, 판매중지, 품절, 미취급 PPC를 각각 다르게 표시한다". 서버 기본값(false)은 호환을 위해 그대로 두니, 고객앱이 `includeNotCarried=true` + 카드에 미취급 표시로 바꾸면 된다(요청서 §3 의 "구분 표시" 경로) |

## BR-45 승인 상세 `requestedAmount: null`
- **(a) 계약 그대로**로 맞췄다. 승인 행(`ApprovalLine`)의 값 없는 `requestedQty`·`approvedQty`·`requestedAmount`·`approvedAmount` 는 **생략**한다(null 을 보내지 않는다). 시험으로 고정했다.
- 다른 모델은 계약에 nullable 필드가 있어 그대로 둔다.
- **§3 "정본 반영 확인" 30초**:
  - 지연이 아니라 **기능이 아직 없다.** 승인의 `executionState` 를 APPLIED·PARTIAL·REJECTED 로 바꾸는 반영 회신(`/internal/application-receipts`, Receipt)이 미구현이라, 결정 뒤 `executionState` 는 VALIDATING 에 머문다.
  - 정본 쪽 반영은 이벤트로 정상 진행된다. 결과는 각 업무 화면에서 확인할 수 있다: 상품 배치 상태·id-map, 가격 배치 `state`, 콘텐츠 배치 `state` 등.
  - 권장: 승인 화면의 30초 대기 뒤 안내를 "반영 결과는 해당 등록 화면에서 확인"으로 두고, Receipt 구현 뒤 다시 맞춘다. Receipt 구현은 백엔드 백로그다(여러 정본 서비스가 함께 바뀌는 일).

## BR-44 콘텐츠 신규 / 기존 게시 갱신 구분
- `ContentDraft`(배치 `items[].draft` 포함)에 추가:
  - `registration`: `NEW` | `UPDATE` | null — 심사 전 초안(DRAFT·SUBMITTED)만. 연결 상품(productId) 또는 같은 원천 키에 이미 게시된 콘텐츠가 있으면 `UPDATE`.
  - `publishedContentVersion`·`publishedDraftId`·`publishedAt` — `UPDATE` 일 때 지금 게시본.
- 승인 요청 행 `lines[].attributes` 에 `registration`, `publishedContentVersion`(UPDATE 일 때).
- 배치 `items[].outcome`(CREATED 등)은 그대로. 요청 4번(별도 outcome)은 `registration` 으로 충분하다고 보고 생략했다.
- 게시된 초안 자신과 반려된 초안은 `registration` 이 null.

## BR-46 ai-assistant 남은 항목
| # | 회신 |
|---|---|
| 6 질문 목록 | **추가**: `GET /api/v1/assistant/question-history`(헤더 `X-Source-System`, `size`·`cursor`) → `QuestionHistoryPage{items[answerId, requestId, state, question, answerPreview, answerMode, citationCount, askedAt], page}`. 본인 + 출처 범위, 최근 순. 질문 원문은 기본 저장하지 않아(ADR-0036) `question` 은 null — 목록은 `answerPreview`(답변 앞 120자)로 구분. 완료 답변은 현재 권한을 다시 확인해 볼 수 없는 것은 뺀다. 처리 중이던 행은 `state: PROCESSING` |
| 4 원문 열람 | 1차에는 문서 열람 API 를 두지 않는다. `citations[].excerpt` 가 근거 청크 전문(자르지 않음)이고 `path`·`section` 이 저장소 위치다 |
| 9 상태 확인 경로 | 게이트웨이는 `/actuator/**` 를 공개하지 않는다(운영용). 화면은 응답 코드로 구분하면 된다 — 503 `DEPENDENCY_UNAVAILABLE`(장애·같은 키로 재시도), 422 `CONFIG_REQUIRED`(색인 없음) |
| 13 LAN 배포 | **보류**(사용자 결정, pgvector 위치 미정 — ADR-0036 결정 필요 2). 게이트웨이 경로 `/api/v1/assistant/**` 는 pmart-config 에 반영됐지만 서비스가 LAN 에 없어 지금 부르면 503. 배포하면 따로 알린다 |
