# [pmart-backend → pmart-clients] 안내: 내 정보에 Google 프로필(이름·이메일·사진) (2026-10-31)

- 계약: `contracts/openapi/customer-service.yaml` — `Profile`(name·email·emailVerified·profileImageUrl 추가), `GET /me/profile-image`(새로)
- 결정: ADR-0024

## 1. 무엇이 바뀌었나
- 지금까지 백엔드는 Google ID 토큰에서 계정 식별자(sub)만 쓰고 이름·이메일·사진을 버렸다. 그래서 `GET /me` 에 이름·이메일이 없었다.
- 이제 로그인 때 Google 이 준 값을 고객 정보에 저장하고 `GET /me` 로 돌려준다.

| 필드 | 내용 |
|---|---|
| `name` | Google 프로필 이름 |
| `email` | Google 이메일 |
| `emailVerified` | Google 이 이메일을 확인했는지 |
| `profileImageUrl` | 우리 서버의 사진 사본 경로 `/api/v1/me/profile-image?v=…`. 게이트웨이 상대 경로다 |

- 로그인할 때마다 Google 의 최신 값으로 바뀐다. 앱에서 이름·이메일을 고치는 API 는 없다(프로필 변경은 닉네임·연락처만, 아직 501).
- 값이 없을 수 있다. 없으면 빈칸이나 기본 아바타로 보여 달라.

## 2. 프로필 사진
- Google 사진 주소를 그대로 주지 않는다. 로그인 뒤 서버가 한 번 받아 우리 저장소에 두고 그 사본을 준다.
  - Google 주소는 바뀌거나 사라질 수 있고, 볼 때마다 Google 에 요청이 가기 때문이다.
- 그리는 법:
  - 주소: 게이트웨이 출처 + `profileImageUrl`. 상품 이미지와 같은 `resolveImageUrl` 을 쓰면 된다.
  - **본인 토큰이 필요하다.** `Image.network(url, headers: {'Authorization': 'Bearer <access token>'})`. 토큰 없이는 401 이다.
  - 사진이 바뀌면 `v` 가 바뀐다. 같은 `v` 는 같은 사진이라 캐시해도 된다.
  - 사본이 아직 없으면 `profileImageUrl` 이 비어 있고, 사진 조회는 404 다.
- 사본은 로그인 사건 처리 직후 비동기로 만든다. 로그인 바로 뒤 `/me` 에는 잠깐 없을 수 있다.

## 3. 앱에서 확인할 것
1. 계약 재생성(`Profile` 필드, `getMyProfileImage`).
2. Google 로그인 ID 토큰에 `email`·`name`·`picture` 가 들어오는지 확인. `google_sign_in` 기본 로그인은 보통 들어온다. 안 들어오면 email·profile scope 를 요청해야 한다.
3. 이미 가입한 계정은 **다음 로그인 때** 채워진다.
