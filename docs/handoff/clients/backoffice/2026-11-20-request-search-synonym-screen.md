# [pmart-backend → pmart-clients] 요청: 백오피스 "검색 동의어 관리" 화면 (2026-11-20)

- 보낸 영역: platform(PC 1)
- 받는 앱: backoffice(`web/backoffice` 상품·전시 묶음) — 담당 PC 2
- 근거: ADR-0073 단계 C(검색 동의어 백오피스 관리·승인·즉시 반영), 사용자 결정 2026-11-20 "MD 요청, 관리자 승인"
- 계약: search-service **0.5.0**(`GET /search/synonym-rules`·`GET /search/synonym-rule-applications`), approval-service **0.5.0**(종류 `SEARCH_SYNONYM`)
- 이미 있는 화면: "결과 없는 검색어"(search 0.4.0 `GET /search/zero-result-keywords`, #263) — 여기서 동의어 초안으로 넘어가는 단추를 더해 주세요
- 서버 상태: **LAN 배포됨**(2026-11-20, approval·search·게이트웨이 3ef008b3). 지금 규칙 없음(version 0)

## 무엇
고객이 "달걀"로 찾아도 "계란" 상품이 나오게, MD 가 같은 뜻 낱말 묶음을 만들고 운영 관리자가 승인하면 **재색인 없이 다음 검색부터** 반영된다.

- 누가 요청: `MD`·`OPERATOR_ADMIN`(그 밖 403 — 메뉴 숨김)
- 누가 승인: `OPERATOR_ADMIN`(승인함, 종류 `SEARCH_SYNONYM`). 자기가 낸 것은 자기 승인 불가
- 규칙 모양: **같은 뜻 묶음만**(예 `계란, 달걀, 에그`). 한 방향 규칙(`콜라 => 코카콜라`)은 지금 받지 않는다

## 흐름
1. **지금 규칙 보기** `GET /api/v1/search/synonym-rules` → `version`(기준 버전), `rules[]`(`ruleId`, `terms[]`), `lastApprovalId`, `updatedAt`
2. **화면에서 바꿀 것 모으기**(서버 초안 없음 — 화면이 들고 있다): 추가·수정 = UPSERT, 삭제 = DELETE
3. **승인 요청** approval-service `POST /api/v1/change-requests` (Idempotency-Key, X-Source-System: backoffice)
   ```json
   {
     "type": "SEARCH_SYNONYM",
     "targetService": "search-service",
     "targetRef": "pmart-product-synonyms",
     "targetExpectedVersion": 0,
     "summary": "검색 동의어 2건 — 계란·달걀 묶음 추가 외",
     "lines": [
       { "lineKey": "egg", "attributes": { "op": "UPSERT", "terms": ["계란", "달걀", "에그"] } },
       { "lineKey": "old-rule", "attributes": { "op": "DELETE" } }
     ]
   }
   ```
   → `POST /change-requests/{id}/submissions` (`X-Expected-Version` = 받은 version)
   - `targetExpectedVersion` = 1번에서 본 `version`(필수)
   - `lineKey` = 규칙 ID(영문·숫자·`_.-` 1~64자, 한 요청 안에서 겹치지 않음, 1~200행). 새 규칙은 화면이 만든다(예 낱말 첫 영문 이름이나 `syn-<짧은 난수>`)
   - UPSERT 의 `terms`: 2~20개, 각 1~30자, 쉼표·`=>`·`#` 없음, 겹치지 않음. 서버가 소문자로 맞춘다
   - 형식이 틀리면 400(요청이 만들어지지 않음) — 필드 옆에 표시
   - 심사 중인 동의어 승인은 **한 번에 하나**(대상이 하나라 409 DUPLICATE_APPROVAL) — 화면에 "승인 대기 중" 표시
4. **승인 뒤 결과** `GET /api/v1/search/synonym-rule-applications` — `result` APPLIED / REJECTED
   - `STALE_VERSION`: 요청 뒤 다른 변경이 먼저 반영됨 → 다시 조회해 새로 요청
   - `LINE_REJECTED`: `lineReasons`(규칙 ID → 까닭, 예 지우려는 규칙이 없음) — 하나라도 틀리면 **아무것도 안 바뀐다**
   - 반려(REJECTED 결정)는 기록 없음 — 승인함 상태로 본다

## "결과 없는 검색어" 화면에서
검색어 줄마다 "동의어 만들기" → 그 검색어를 첫 낱말로 넣은 UPSERT 줄을 2번 목록에 더한다(같은 뜻 상품 낱말은 MD 가 채움).

## 확인용
- LAN 에서 MD 계정과 운영 관리자 계정이 달라야 끝까지 확인된다
- 반영되면 고객 앱 검색 `GET /api/v1/search/products?q=달걀` 이 "계란" 상품을 찾는다(다음 검색부터)

## 처음 동의어 목록(단계 E)
백엔드가 LAN 상품 약 500개 기준 초안을 만들어 사용자 검토 → 이 화면(또는 같은 승인 요청)으로 넣는다. 화면이 먼저 생기면 그걸로 넣는다.
