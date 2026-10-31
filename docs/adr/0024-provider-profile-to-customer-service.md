# ADR-0024: 로그인 제공자(Google)가 준 프로필은 버리지 않고 customer-service 에 넘긴다

- 상태: 제안
- 날짜: 2026-10-31
- 대체: ADR-0019 의 "제공자 토큰·이메일은 저장하지 않는다" 구현 해석(검증 결과에서 `sub` 만 꺼내던 것)
- 관련: ADR-0019(고객 Google 로그인), Commerce "내 정보"(닉네임·이름·이메일), 사건 `CustomerAuthenticated`(platform.auth.v1)

## 배경
- ADR-0019 는 auth-service 에 제공자 토큰·이메일을 **저장하지 않기로** 했다. 구현은 이를 "꺼내지도 않는다"로 좁혀, Google ID 토큰을 검증한 뒤 `sub` 만 남기고 이름·이메일·사진을 버렸다.
- 그 결과 고객 정보 정본인 customer-service 의 `customer.name`·`email` 을 채울 원천이 없었다. 업무 문서의 "내 정보"(닉네임·이름·이메일)를 보여 줄 수 없다.
- 저장 위치(auth 에 두지 않음)와 전달(customer 로 넘김)은 다른 문제다.

## 결정
1. auth 의 `GoogleIdTokenVerifier` 는 검증한 토큰에서 `sub`, `email`, `email_verified`, `name`, `picture` 를 꺼낸다. 값은 앱이 요청한 scope 에 따라 없을 수 있다.
2. auth 는 여전히 `sub` 만 저장한다(`social_identity`). 이름·이메일 컬럼을 두지 않는다.
3. `CustomerAuthenticated` 사건에 `email`, `emailVerified`, `name`, `pictureUrl` 를 **선택 필드로 추가**한다(필드 추가 — 하위 호환). 없는 값은 싣지 않는다.
4. customer-service 가 사건을 받아 `customer.name`·`email`·`email_verified`·`profile_image_source_url` 을 채운다.
   - **로그인마다 최신 값으로 갱신**한다. 이름·이메일은 제공자가 정본이고, 고객이 직접 바꾸는 계약 경로가 없다(프로필 변경은 닉네임·연락처만).
   - 사건에 없는 값은 기존 값을 유지한다. 인증 시각이 지금까지 중 가장 최근인 사건만 덮어쓴다(늦게 도착한 옛 사건이 되돌리지 않음).
5. **프로필 사진은 제공자 URL 을 그대로 주지 않는다.** 그 URL 은 바뀌거나 사라질 수 있고, 볼 때마다 제공자에 요청이 가며(장애·제한·사용 기록 노출), 크기를 우리가 정할 수 없다.
   - 로그인 사건 반영이 커밋된 뒤, 원본 URL 이 지난번 사본의 원본과 다를 때만 customer-service 가 트랜잭션 밖에서 받는다(가상 스레드).
   - 받는 곳은 허용 호스트(`googleusercontent.com` 하위)의 https 만, 리다이렉트는 따라가지 않는다(SSRF 방지). 크기는 `=s256-c`, 본문 상한 1MB, 형식은 본문 머리로 판정(JPEG·PNG·WebP·GIF).
   - S3 비공개 버킷 `pmart-customer` 의 `profile-images/<accountId>/<sha256>` 에 둔다(ADR-0016 저장소). 사본이 바뀌면 옛 객체를 지운다.
   - 앱은 `GET /api/v1/me/profile-image`(본인 토큰)로 읽는다. `Profile.profileImageUrl` = `/api/v1/me/profile-image?v=<지문 앞 12자>`.
   - 받기 실패(네트워크·제공자·저장소)는 기존 사본을 두고 다음 로그인 때 다시 받는다. 허용 밖 주소·이미지 아님은 다시 받지 않는다. 버킷이 설정되지 않으면 사본 기능만 꺼진다.
6. `GET /me` 가 `name`, `email`, `emailVerified`, `profileImageUrl`(우리 사본 경로) 을 돌려준다.
7. 이메일이 같다는 이유로 계정을 합치지 않는다(Commerce 4.9, ADR-0019 그대로). 이메일은 표시·연락 정보일 뿐 계정 식별자가 아니다.

## 결과
- 개인정보는 customer-service 한 곳(`customer`)에 모인다. 로그·사건 소비 로그에 남기지 않는다.
- auth 의 Outbox 행에 사건 본문이 잠시 담긴다. 기본 설정(`delete-after-insert`)은 넣은 즉시 지우므로 auth DB 에 쌓이지 않는다. Kafka 토픽 보존 기간 동안은 사건 본문에 남는다.
- 탈퇴 완료 시 customer 의 이 값들과 S3 사진 사본을 지우는 일은 탈퇴 구현 때 함께 한다.
