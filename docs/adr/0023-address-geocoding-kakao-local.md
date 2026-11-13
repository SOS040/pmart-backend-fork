# ADR-0023: 주소 ↔ 좌표는 카카오 로컬(서버 호출), 배송 가능 판정은 도로명주소 좌표 — 지도 핀은 배달 위치 참고

- 상태: 제안
- 날짜: 2026-10-31
- 관련: 클라이언트 요청 BR-30(`pmart-clients/docs/handoff/clients/2026-10-31-backend-address-geocoding.md`), 회신 `docs/handoff/clients/archive/2026-10-31-clients-reply-address-geocoding.md`, ADR-0022(guest-paths), Commerce 5.6·5.7

## 배경
- 배송 가능 판정(담당 MFC)은 좌표로만 했고, 고객 앱은 주소를 좌표로 바꿀 수단이 없어 회원 주소 판정이 늘 UNKNOWN 이었다.
- 사용자 결정(2026-10-31): 주소 좌표는 **카카오**로 얻고, 키가 필요한 변환은 서버가 한다. 앱은 우편번호 검색(다음)과 카카오 지도 핀 조정만 한다.
- Commerce 5.6: "정규화한 주소와 **검증 가능한 위치 근거**로 매핑을 조회한다. 임의 좌표·행정동 추측으로 센터를 정하지 않는다." 핀은 고객이 제한 없이 옮길 수 있어 검증 가능한 근거가 아니다.
- Commerce 723·SCR-ADDRESS-EDIT-001: 비회원도 배송지를 설정한다.

## 결정
1. **위치**: 주소 정규화·판정의 정본인 delivery-serviceability-service 에 카카오 로컬 어댑터를 둔다. REST API 키는 환경변수(`PMART_KAKAO_REST_API_KEY`)로만. 없으면 해당 기능만 CONFIG_REQUIRED.
2. **화면용 API**: `GET /address-geocodes?query=`(주소 → 좌표, 유사 검색), `GET /address-reverse-geocodes?lat=&lng=`(좌표 → 주소, 핀 위치 표시). 결과는 저장하지 않는다. 0건은 200 + 빈 목록.
3. **권한**: 고객과 비회원(사용자 결정 2026-10-31, guest-paths 에 추가). 토큰 없는 호출은 401 — 무료 사용량 보호.
4. **판정 좌표(사용자 결정 (b))**: `POST /serviceability-checks` 는 roadAddress 를 서버가 카카오 주소 검색(정확 일치)으로 바꾼 좌표로 판정한다. 요청의 lat·lng(고객 지도 핀)는 판정에 쓰지 않고, customer-service 가 배달 위치 참고로 저장한다.
   - roadAddress 없음·0건·서로 다른 여러 곳 → NEEDS_INPUT(missingFields=roadAddress). 임의로 고르지 않는다. 같은 좌표의 중복은 하나로 본다.
   - 카카오 장애·사용량 소진 → UNKNOWN(가능·불가를 추측하지 않음). 키 미설정 → 422 CONFIG_REQUIRED.
   - 예외: `purpose=TEST_ADDRESS` 는 **운영자 토큰**일 때만 요청 좌표로 판정한다(권역 시험 좌표). 운영자가 아니면 403.
5. **오류 코드(사용자 결정)**: 카카오 장애·시간 초과는 공통 `DEPENDENCY_UNAVAILABLE`(503, retryable), 사용량 소진만 `QUOTA_EXCEEDED`(429), 키 거절(401·403)은 CONFIG_REQUIRED.
6. **개인정보**: 주소 질의·결과·키를 로그에 남기지 않는다. 외부 호출은 판정 트랜잭션 밖에서 한다.

## 대안
- 앱이 카카오를 직접 호출: REST 키가 앱에 들어간다(비밀값 규칙 위반).
- 핀 좌표로 판정(a/c): 고객이 핀을 다른 권역으로 옮겨 담당 MFC 를 바꿀 수 있다(Commerce 5.6 위반). 거리 제한(a)은 기준값 결정이 필요해 보류.
- customer-service 에 두기: 주소 정규화는 serviceability 책임(service-map §7).

## 결과
- customer-service 는 코드 변경 없음. `AddressInput.geo` 의 의미가 "배달 위치 참고(핀)"로 바뀌었고(계약 설명), 판정이 실제 도로명주소를 요구한다. 임의 문자열 roadAddress 는 NEEDS_INPUT.
- seed·운영 시험은 TEST_ADDRESS 를 운영자 토큰으로 부른다. 매핑 버전의 시험 주소 검증(내부)은 좌표 그대로.
- 카카오 무료 사용량(주소 검색·좌표→주소 각 하루 10만 건, 비즈월렛 미연결 시 초과분은 과금 없이 429). 캐시는 아직 없다.
- 남은 일: 공통 라이브러리가 쿼리 제약 위반(ConstraintViolationException)을 500 으로 내는 문제 — 이 서비스는 `QueryValidationAdvice` 로 400 처리, 공통화는 별도. 카카오 콘솔 호출 허용 IP 등록, 개인정보 처리 위탁 고지 검토.
