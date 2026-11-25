# 네이버 가입 뒤 프로필 사진·이름·이메일이 비어 보임 — 진단 (2026-11-25)

- 계기: 고객 앱 BR-103(`pmart-clients docs/handoff/customer-app/2026-11-25-backend-naver-profile-missing.md`) — 사용자 실기기(갤럭시 A53) "네이버로 로그인했는데 이름·프로필·이메일 다 안 받는다", "처음 소셜 로그인 이후 다시 로그인해야 이메일이 뜬다", "이름이랑 프로필은 여전히 안 된다".
- 네이버 개발자센터 제공 정보: 회원이름 필수, 연락처 이메일 필수, 프로필 사진 추가.
- 회신: `docs/handoff/clients/customer-app/2026-11-25-reply-br103-naver-profile.md`. 같은 날 앞선 고침: auth V9 네이버 이름(`docs/worklog/platform/2026-11-25.md`).

## 흐름(어디서 값이 오나)
| 단계 | 하는 일 | 값 |
|---|---|---|
| 1 | 앱 → auth `POST /auth/naver-token-exchanges`(인가 코드) | — |
| 2 | auth 가 네이버 토큰 교환 → **네이버 액세스 토큰으로** `GET https://openapi.naver.com/v1/nid/me` 한 번 | `profile_image`·`email`·`name`(없으면 `nickname`) |
| 3 | 사진 주소는 교환 응답 `profileImageUrl` 로 앱에, 이메일·이름은 id_token `sub` 에 10분 묶음(`naver_profile_email`) | — |
| 4 | 앱이 로그인·동의 완료·휴대폰 확인에 `profileImageUrl` 를 실어 보냄 → 회원이 되는 순간 `CustomerAuthenticated`(platform.auth.v1)에 이름·이메일·사진 주소 | 사건 |
| 5 | customer-service 가 사건을 반영(이름·이메일) → 커밋 뒤 사진 주소에서 사진을 받아 사본(`profile-images/<계정>/<sha256>`, 256px) | `/me` |

- 사진 값은 **토큰 안에 없다**: 네이버 액세스 토큰은 불투명 문자열(JWT 아님), 네이버 ID 토큰에는 `picture` 가 없다(서버 코드는 읽게 되어 있지만 안 실림), PMart 토큰에도 넣지 않는다. 사진은 2단계 프로필 API 에서만 온다.

## 확인한 것(LAN, 값은 안 봄 — 있음/없음만)
- 2026-11-25 네이버 가입 2건(05:34·05:56 UTC — 각 38초·47초 만에 끝남)과 그 뒤 로그인 2건: auth 사건 4건 모두 `name`·`email`·`pictureUrl` **있음**(Kafka `platform.auth.v1`). customer-service 로그 "고객 인증 반영" 4번.
- 같은 때마다 customer-service 로그 **"프로필 사진 응답이 없거나 상한 초과 … 다음 로그인 때 다시"**(05:35:17·05:57:09·05:57:31·05:59:09).
- 사건의 사진 주소를 직접 받아 봄: `phinf.pstatic.net` 200 `image/png` **1,451,016 바이트** — customer 사본 상한 `pmart.customer.profile-image.max-bytes` 1,048,576(1MB)을 넘음.
- 같은 주소에 `?type=s160`: 200 `image/png` 25,536 바이트 — 네이버 CDN 이 크기 지정 축소를 지원.
- 가입 완료(휴대폰 확인 VERIFIED 05:35:15.7) → customer 반영(05:35:16.9) 약 **1초** 차이.
- auth "네이버 교환 이메일 1건 파기(보관 만료)" 1건은 가입을 끝내지 않은 교환 — 이번 두 가입과 관계없음.

## 원인
1. **사진 — 서버 결함**: 네이버 프로필 원본이 1.45MB PNG 라 사진 사본이 1MB 상한에 걸려 매번 버려졌다. Google 은 주소 끝 `=s96-c` 를 `=s256-c` 로 바꿔 작게 받는데, 네이버 주소는 원본 그대로 받았다.
2. **첫 가입 직후 이름·이메일이 빔 — 타이밍**: 값은 첫 가입 사건에 있지만 customer 반영이 로그인 완료 약 1초 뒤다. 앱이 그 전에 `/me` 를 읽고 다시 읽지 않아 "다시 로그인해야 이메일이 뜬다".
3. **다시 로그인해도 이름이 빔 — 앱 표시**: 앱 내 정보 줄이 `nickname` 만 보고 서버 `name` 을 안 봤다(앱에서 고침, 2026-11-25).
- 의심했던 10분 보관 만료(요청서 Q1)는 이번 원인이 아니다 — 가입이 1분 안에 끝났다.

## 고친 것
- customer-service `domain/ProfileImageRules.sized`: 호스트가 `*.pstatic.net` 이면 `?type=s<size-px>`(지금 256)로 받는다 — 같은 사진 약 25KB. 시험 `ProfileImageRulesTest.naverPhotoAsksCdnForSmallSize`. 커밋 52b1d27f.
- 사본은 로그인 사건마다 다시 시도하므로, 배포 뒤 다음 네이버 로그인 때 사진이 들어간다(이미 있는 회원도).
- 앱 요청(회신): 가입·로그인 직후 `/me` 에 `name`·`email` 이 없으면 2~3초 뒤 한 번 더 읽기(사진 `profileImageUrl` 도).

## 다음에 같은 증상이면
1. Kafka `platform.auth.v1` 의 `CustomerAuthenticated`(provider NAVER)에 `name`·`email`·`pictureUrl` 키가 있는지(값은 출력하지 않는다).
2. customer-service 로그 "고객 인증 반영"·"프로필 사진 …" 줄과 시각.
3. 사진이면 주소의 상태·크기만(주소 원문은 남기지 않는다) — 상한(1MB)·허용 호스트(`allowed-host-suffixes`)·리다이렉트(따르지 않음) 확인.
4. auth 로그 `네이버 프로필 … 생략 reason=` — `missing_name`·`missing_email`·`missing_profile_image` 면 네이버가 안 준 것(동의 화면에서 끔·콘솔 제공 정보).

## 덧붙임 — 첫 고침이 오히려 막음(같은 날 오후, 고객앱 `2026-11-25-backend-br103-photo-still-missing.md`)
- 배포(4dc42be7, 06:19 UTC) 뒤 07:04·07:08 로그인도 "응답이 없거나 상한 초과". 원인: 네이버 CDN 은 **정해진 크기만** 준다 — `?type=s80`·`s160`(=`f160_160`)은 200, `s256`·`s100`~`s640`·`w240` 등은 **404**. 고친 코드가 `?type=s256` 을 붙여 늘 404 였다(이번 계정 원본은 79KB 라 원본이면 됐다).
- 고침: 네이버는 `?type=s160` 고정(`ProfileImageRules.NAVER_TYPE`), 크기 바꾼 주소가 안 되면 **원래 주소로 한 번 더**(`ProfileImageRules.original`, 1MB 상한 그대로) — `ProfileImageService.copy`.
- 그 계정(CU-2cde…)의 사진 출처 기록: 07:08:23 NONE → 07:08:26 PROVIDER(되돌리기) → 받기 실패 → 07:13:30 NONE. NONE·CUSTOM 이면 로그인해도 덮지 않는다(BR-80) — 되돌리기(`POST /me/profile-image/provider-restorations`)가 PROVIDER 로 바꾸고 다시 받는다. 계정은 그 뒤 탈퇴.
- 교훈: CDN 크기 파라미터는 지원 값을 실제로 받아 보고 정한다 — 한 값(s160)만 시험하고 다른 값(s256)을 썼다.

## 덧붙임 — 카카오(같은 날, 사용자 "카카오도 확인해줘")
- 07:17 UTC 카카오 로그인 사건: `email` 있음·`pictureUrl` 있음(`http://k.kakaocdn.net/…` — 카카오 ID 토큰 picture 는 **http**)·`name` 없음.
- 사진: customer 가 "원본이 허용 범위 밖이라 받지 않음"으로 버렸다 — https 만 받는 규칙. 같은 주소를 https 로 받으면 200(4.5KB) → **카카오 CDN(`*.kakaocdn.net`)만 http → https 로 바꿔 받는다**(`ProfileImageRules.fetchUri`, 다른 호스트 http 는 그대로 거절). 시험 `ProfileImageRulesTest.kakaoHttpPhotoIsUpgradedToHttps`.
- 이름: 카카오 사용자 정보(Admin 키)에 **닉네임 동의 항목 자체가 없다**(`profile_nickname_needs_agreement` 키 없음) — 서버가 받을 값이 없다. 카카오 개발자 콘솔 → 카카오 로그인 → 동의항목에서 **"닉네임"(profile_nickname)** 을 켜야 ID 토큰 `nickname`·사용자 정보 `profile.nickname` 이 온다(auth 는 둘 다 읽는다). 실명 "이름"(name)은 비즈 앱 심사가 필요하다.
- 이 계정의 사용자 정보는 지금 이메일·사진 모두 `needs_agreement: true`(동의 안 함·다시 동의 필요) — 동의 화면에서 이메일·사진을 고르지 않으면 값이 안 온다.

