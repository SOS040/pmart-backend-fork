# ADR-0075 홈 랭킹·신상품 탭과 일반 배너·상품 테마 편성(BO-05)

- 상태: **승인** (2026-11-20)
- 사용자 결정: "프로모션부터 하자" → 범위 "탭 + 배너·테마"(할인·쿠폰 없음)
- 근거:
  - Commerce §1.0: 프로모션·할인·쿠폰은 1차 제외
  - Commerce 홈 탭 규칙: 신규 판정기간·순위 집계기간·평가 기준은 승인 설정, 참고의 입점 2주·TOP30을 기본값으로 고정하지 않음
  - BackOffice BO-05: 일반 배너·상품 테마·노출 순서·이미지·링크·대상·기간 → 권한자 게시 승인 → 게시 → 실제 노출 버전 확인. 할인율·쿠폰·특가·증정이 든 안은 1차에 게시하지 않음
- 관련: ADR-0072(순위 신호), product-content 콘텐츠 게시(DISPLAY 승인, 초안 해시 잠금), ADR-0016(이미지는 S3), ADR-0039(반영 회신)
- 진행: `docs/operations/2026-11-20-pending-identity-switch-and-home-promotion.md` §2

## 결정
### ① 랭킹·신상품 탭 (storefront 0.6.0 — 구현 27d08273)
- **정책값:** `pmart.storefront.tabs.new-window-days`(신규 판정기간)와 `ranking-size`(랭킹 상품 수)다.
  - 코드 기본값은 없다. 값이 없으면 그 탭은 `tabs`에서 빠지고 요청은 404다.
  - 순위 집계기간은 순위 신호 설정(ADR-0072, 30일)이다.
- **랭킹:** 최근 판매 많은 순으로 줄 세운다. 판매가 같으면 베이지안 별점 점수, 그다음 상품 ID 순이다.
  - 판매 0인 상품은 뺀다.
  - 1위부터 ranking-size까지만 보인다(badge RANK·rank).
- **신상품:** 처음 게시된 때가 신규 기간 안인 상품을 최신 순으로 보인다(badge NEW). 다른 영역에서도 신규 기간 안이면 NEW를 붙인다.

### ② 일반 배너·상품 테마 편성 (storefront 0.7.0)
1. **정본은 storefront다.** 진열 구성(배너·테마)은 버전으로 게시한다.
   - `layout_version`은 1부터다. 0은 기본 진열이다.
   - 게시된 버전의 본문은 바꾸지 않는다. 고치려면 새 초안을 만들어 새 버전으로 게시한다(전후 비교는 버전끼리).
2. **초안**(운영자 MD·OPERATOR_ADMIN):
   - 경로: `POST/GET/PUT /storefront/layout-drafts`(`?fromCurrent=true`면 지금 게시본을 복사해 시작)
   - 본문:
     - `banners[]`: placement HOME_TOP·HOME_MIDDLE, imageId, altText, linkType SECTION·CATEGORY·PRODUCT·NONE, linkRef, sortOrder, startsAt·endsAt
     - `themes[]`: sectionId, kind THEME·SEASON·WEEKLY_PICK, title, subtitle, productIds 1~50개(순서 그대로), sortOrder, startsAt·endsAt
   - 수정 중인 초안은 고객 화면을 바꾸지 않는다.
3. **제출** `POST /storefront/layout-drafts/{id}/submissions`: 검사를 통과하면 SUBMITTED로 잠그고 `draftHash`를 확정한다. 응답의 `approvalRequest`를 백오피스가 approval-service로 그대로 보낸다(콘텐츠 게시와 같은 방식).
   - 검사 항목과 실패하면 422로 돌려주는 사유:
     - 상품이 진열할 수 있는 상태인지(게시·판매중지 아님) → PRODUCT_NOT_PUBLISHED
     - 이미지가 올라가 있는지
     - 링크 대상이 맞는지(SECTION은 같은 초안의 테마)
     - 기간이 맞는지(startsAt < endsAt)
     - 1차 범위 문구 — 제목·부제·대체 문구에 할인·"N% off"·쿠폰·특가·증정·사은품·1+1·세일이 있으면 PROMOTION_NOT_ALLOWED. 근거 없는 "최저가·신선 보장"도 같은 사유로 막는다. "100% 국내산"처럼 할인이 아닌 %는 막지 않는다
     - 같은 초안 안 배너 ID·테마 영역 ID 중복(DUPLICATE_ID), 한 테마 안 같은 상품 중복(DUPLICATE_PRODUCT)
     - 모든 걸린 곳을 한 번에 `LAYOUT_INVALID`(422)의 details(필드·사유)로 돌려준다. 배너·테마가 없는 본문도 제출할 수 있다(⑧ 철회)
4. **승인:** approval 종류 **DISPLAY**(정본 `storefront-service`, targetRef = draftId, 행 attributes draftHash)를 쓴다. 승인자 `approver-roles.DISPLAY` = DISPLAY_APPROVER로, 지금 설정 그대로다. 자기 승인은 금지다.
5. **게시:** storefront가 `ApprovalDecided`(DISPLAY·storefront-service)를 받는다.
   - **APPROVED**이고 draftHash가 같으면 → 새 `layout_version`을 게시하고 `ApplicationReceiptRecorded`로 회신한다. 해시가 다르면 SCOPE_MISMATCH로 반영하지 않는다.
   - **REJECTED**면 → 초안은 REJECTED가 된다. 고쳐서 다시 제출한다.
   - inbox 안에서 처리하고, 승인 ID마다 한 번만 반영한다.
   - 운영자가 따로 누르는 `POST /storefront/layout-publications`는 쓰지 않는다(계약에서 승인 반영으로 대체).
6. **노출:**
   - HOME 탭 = 지금 버전의 배너(기간 안) + 테마 영역(기간 안, sortOrder 순) + 마지막에 "전체 상품" 영역
   - 테마 항목은 넣은 순서 그대로이고, 진열할 수 있는 상품·센터 취급 상품만 남긴다. 판매중지는 그 자리에서 빠지고 영역은 남는다.
   - 배너 목록 `GET /storefront/banners?placement=`
   - 응답의 `layoutVersion`은 지금 버전이다. 영역 다음 묶음에서 버전이 다르면 409 LAYOUT_VERSION_CHANGED다.
7. **배너 이미지:**
   - 올리기 `POST /storefront/banner-images`(운영자, JPEG·PNG·WEBP 5MB 이하) → imageId
   - 보기 `GET /storefront/banner-images/{imageId}`(공개, 게시된 버전에 쓰인 이미지만, 1년 immutable — 이미지 ID마다 본문이 바뀌지 않는다)
   - 저장은 S3 버킷 `pmart-storefront`(ADR-0016, 앱이 버킷을 만든다)
8. **철회:** 배너·테마를 뺀 새 초안을 게시하거나 endsAt으로 끝낸다. 과거 버전·승인·대상은 남는다.

## 결과
- 홈에 배너와 테마 영역이 생긴다. 앱 화면은 계약 그대로라(`HomeLayout.banners`·`sections`) 고칠 것이 적다. 배너 탭 동작과 SECTION 링크는 앱 확인이 필요하다.
- 백오피스에는 편성 화면(초안·이미지 올리기·제출·승인함 연결)이 필요하다. 백오피스 웹 요청서로 보낸다.
