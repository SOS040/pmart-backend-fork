# [pmart-backend → pmart-clients] ① 주소·쇼핑 주소 API 안내 (2026-10-30)

- 관련: `2026-10-30-clients-reply-customer-app-priority.md` 의 ①
- 계약 정본: `contracts/openapi/customer-service.yaml`, `contracts/openapi/delivery-serviceability-service.yaml`
- 모든 변경 요청은 `Idempotency-Key`(재시도 때 같은 값)·`X-Source-System: customer-app` 필수

## 1. 회원 (고객 토큰 `typ=CUSTOMER`)
| 작업 | API | 메모 |
|---|---|---|
| 내 프로필 | `GET /api/v1/me` | 첫 로그인 직후에도 200. `socialProvider` 는 로그인 사건 반영 후 채워짐 |
| 주소록 + 현재 쇼핑 컨텍스트 | `GET /api/v1/me/addresses` | `{items[], shoppingContext}` |
| 주소 추가 | `POST /api/v1/me/addresses` (201) | 본문 `AddressInput`. **`geo{lat,lng}` 를 꼭 보내 주세요** — 배송 가능 판정은 좌표로만 한다(주소 검색 결과의 좌표). 없으면 `serviceability.state=UNKNOWN` |
| 주소 단건·수정·삭제 | `GET/PATCH/DELETE /api/v1/me/addresses/{addressId}` | 수정은 `X-Expected-Version`(주소 `version`) 권장. 현재 쇼핑 주소는 삭제 불가(409 `INVALID_STATE`) |
| 쇼핑 주소 선택 | `POST /api/v1/me/shopping-address-selections` `{addressId}` | 선택 때 다시 판정. `shoppingContextVersion` 이 오르고 응답에 `mfcId`. `X-Expected-Version` = 현재 `shoppingContextVersion`(낡으면 409) |
| 현재 쇼핑 컨텍스트 | `GET /api/v1/me/shopping-context` | 선택 전이면 `shoppingAddressId=null`, 버전 0 |

## 2. 비회원 (토큰 없음)
- `POST /api/v1/guest-delivery-addresses` + `X-Device-Id`(기기별 고정값, 128자 이내) → 201 `Address`(판정 포함)
- `GET /api/v1/guest-delivery-addresses/{guestAddressId}` + 같은 `X-Device-Id` → 200, 다른 기기 403
- 배송 가능 지역만 확인: `POST /api/v1/serviceability-checks` `{address:{roadAddress, lat, lng}, purpose:"ADDRESS_BOOK"}` — 이제 토큰 없이 가능(결과 `SERVICEABLE/OUT_OF_AREA/OVERLAP/NEEDS_INPUT/UNKNOWN`)

## 3. 판정 표시 (`Address.serviceability.state`)
- `SERVICEABLE` → 담당 MFC 확정(`mfcId`, `serviceabilityVersion`)
- `OUT_OF_SERVICE_AREA` → 배송 불가 지역
- `UNKNOWN` → 확인 중/확인 불가(중첩 권역·좌표 없음·일시 장애·권역 미설정). **주소는 저장됐다** — 배송 가능으로 표시하지 말고 "확인 중"으로. 쇼핑 주소로 다시 선택하면 재판정한다. `asOf.freshness=UNAVAILABLE` 이면 판정 자체를 못 한 것
- 오류: 주소록 한도 초과 422 `ADDRESS_LIMIT_EXCEEDED`(현재 20개)

## 4. LAN 상태
- 배포 후 이 문서 아래에 결과를 적는다. **지금 LAN 에는 발효된 권역 매핑이 없어 판정이 `UNKNOWN`** 으로 나온다. ② 시험 데이터 적재 때 서울 시험 권역 1개(승인 경로)를 넣고 좌표 예시를 알린다.
