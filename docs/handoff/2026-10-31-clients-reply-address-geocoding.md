# [pmart-backend → pmart-clients] 회신: 주소 ↔ 좌표 변환 API (BR-30, BR-29 후속) (2026-10-31)

- 요청: `pmart-clients/docs/handoff/2026-10-31-backend-address-geocoding.md`
- 결정 기록: ADR-0023. 계약: `contracts/openapi/delivery-serviceability-service.yaml`(geocoding 태그), `customer-service.yaml` `AddressInput.geo` 설명

## 1. 확인 요청에 대한 답

| 질문 | 답 |
|---|---|
| 경로·서비스 | `GET /api/v1/address-geocodes?query=`, `GET /api/v1/address-reverse-geocodes?lat=&lng=`. 주소 정규화·판정 정본인 **delivery-serviceability-service**. 게이트웨이 경로는 같다. |
| 권한 | **고객과 비회원 토큰 둘 다**(사용자 결정). 업무 문서상 비회원도 배송지를 등록하기 때문이다. 토큰 없이는 401(무료 사용량 보호). |
| 좌표 → 주소(3.2) | 이번에 함께 넣었다. |
| `geo` 를 그대로 믿을지 | **판정에 쓰지 않는다**(사용자 결정 (b)). 아래 2장. |
| 오류 코드 | 카카오 장애·시간 초과는 공통 코드 **503 `DEPENDENCY_UNAVAILABLE`**(retryable=true). 요청안의 `LOOKUP_FAILED` 대신이다. 사용량 소진은 **429 `QUOTA_EXCEEDED`**. 키 미설정·거절은 422 `CONFIG_REQUIRED`. 0건은 200 + 빈 `results`. 입력 형식 오류는 400. |
| 토스 키 정본 | `.secrets/toss-secret-key`(백엔드 render 가 `PMART_TOSS_SECRET_KEY` 로 넘김). `backend.env` 의 `TOSS_SECRET_KEY` 는 같은 값의 중복이었다. 카카오 키는 `.secrets/kakao-rest-api-key` 로 옮겼고 `backend.env` 는 지웠다(사용자 요청). |
| LAN 배포 | 아직 안 했다. 배포하면 이 문서 5장에 적는다. |

## 2. 판정 좌표 — 지도 핀은 배달 위치 참고 (중요)
- 배송 가능 판정(담당 MFC)은 **서버가 `roadAddress` 를 카카오 주소 검색(정확 일치)으로 바꾼 좌표**로 한다.
- 앱이 보내는 `geo{lat,lng}`(지도 핀)은 **판정에 쓰지 않는다.** customer-service 가 배달 위치 참고로 저장할 뿐이다.
- 이유: 업무 문서 Commerce 5.6 "검증 가능한 위치 근거로 판정, 임의 좌표로 센터를 정하지 않는다". 핀은 고객이 자유롭게 옮길 수 있어서, 핀으로 판정하면 다른 권역의 MFC 로 판정받을 수 있다.
- 그래서 **`roadAddress` 는 우편번호 검색에서 고른 실제 도로명주소여야 한다.**
  - 판정 결과는 `NEEDS_INPUT`(missingFields=`roadAddress`)이 된다: roadAddress 가 비었거나, 검색 0건이거나, 서로 다른 여러 곳이 나올 때.
  - 카카오 장애·사용량 소진이면 판정은 `UNKNOWN`. 주소는 저장되고, 쇼핑 주소를 고를 때 다시 판정한다.
- **개발 빌드의 "시험 좌표(서면역)" 버튼은 이제 SERVICEABLE 이 나오지 않는다.** 도로명이 "[시험] 부산 부산진구 서면역 부근"이면 NEEDS_INPUT 이다. 버튼이 권역 안의 **실제 도로명주소**를 넣도록 바꿔야 한다. LAN 배포 뒤 판정을 확인한 예시 주소를 5장에 적는다.

## 3. API

### 3.1 `GET /api/v1/address-geocodes?query=<주소>` (고객·비회원 토큰)
- query 1~200자. 유사 검색이라 여러 건이 나올 수 있다(최대 10). 지도 시작 위치를 정하는 용도다.
- 응답:
  ```json
  { "results": [ { "roadAddress": "부산 부산진구 …", "jibunAddress": "…", "zoneNo": "47285",
                   "buildingName": "…", "lat": 35.1578, "lng": 129.0597 } ] }
  ```
  `roadAddress`·`zoneNo`·`buildingName` 은 없을 수 있다(지번만 있는 주소).

### 3.2 `GET /api/v1/address-reverse-geocodes?lat=&lng=` (고객·비회원 토큰)
- 핀을 옮겼을 때 "이 위치: ○○로 12" 표시용. 응답 모양은 3.1 과 같고, lat·lng 는 요청 값 그대로다. 바다 등은 0건.
- 표시용일 뿐이다. 여기서 나온 도로명으로 `roadAddress` 를 바꾸지 말 것(고객이 고른 주소가 정본).

### 3.3 기존 API 변화
- `POST /me/addresses`·`/guest-delivery-addresses`: 계약 모양은 그대로다. `geo` 의 의미만 "배달 위치 참고(핀)"로 바뀌었다.
- `POST /serviceability-checks` 를 앱이 직접 부른다면: 판정은 `roadAddress` 로만 한다. `purpose=TEST_ADDRESS` 는 운영자 전용(403)이다.

## 4. 그 밖
- 주소 질의·결과는 서버 로그에 남기지 않는다. 캐시는 아직 없다(필요해지면 추가).
- 카카오 무료 사용량: 주소 검색·좌표→주소 각 하루 10만 건. 넘으면 429 `QUOTA_EXCEEDED` 다(과금 없음).
- 카카오 콘솔의 "호출 허용 IP" 등록은 LAN 서버 외부 IP 를 확인해 사용자에게 따로 안내한다.

## 5. LAN 상태
- 배포됨(2026-10-31): delivery-serviceability(3fb1e6c), api-gateway(6f5b931). 게이트웨이에서 토큰 없음 401, 빈 query 400 확인.
- **카카오 호출은 아직 안 된다.** 카카오가 403 "App(PMart) disabled OPEN_MAP_AND_LOCAL service." 를 준다. 카카오 개발자 콘솔에서 PMart 앱의 카카오맵 사용 설정이 꺼져 있다. 사용자가 켜야 한다.
  - 그동안 `/address-geocodes`·`/address-reverse-geocodes` 와 고객 주소 판정은 422 `CONFIG_REQUIRED` 다. 주소 저장은 되고 판정은 UNKNOWN 이다.
- 켜진 뒤 실제 판정으로 확인한 시험 도로명주소를 여기에 적는다.
