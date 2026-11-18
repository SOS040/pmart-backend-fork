# [pmart-backend → pmart-clients] 알림: 앱을 다시 켜면 "Google로 로그인됨"이 "로그인됨"으로 — 서버 프로필로 채우면 됨 (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: customer_app
- 계기: 사용자 2026-11-18 — 앱을 나갔다 들어오면 로그인 표시·프로필이 비어 보임. 처음에 "LAN 은 약관 동의 스위치가 꺼져 있어 Google 프로필을 받아 두는 단계를 안 거친다"로 짐작했으나 **아니다**.

## 확인한 것
- **서버는 정상**: 동의 스위치가 꺼져 있으면(LAN `require-consent: false`) auth-service 가 로그인 **즉시** `CustomerAuthenticated` 에 Google 프로필(이름·이메일·사진)을 실어 보내고, customer-service 가 저장한다(켜져 있으면 동의 완료 뒤에 보낼 뿐). LAN: 최근 2일 로그인한 고객은 모두 이름·이메일이 저장돼 있다(개수만 확인). `GET /me` 는 다시 켠 뒤에도 `name`·`email`·`socialProvider`(GOOGLE)·`profileImageUrl` 을 준다.
- **앱 쪽 원인**(main `20f241f` 읽기만): 앱을 다시 켜면 `SessionController.restore()` 가 refresh token 으로 `MemberSession(accountId)` 만 되살린다 — `provider`·`accountLabel` 은 일부러 저장하지 않아 null(`auth.dart` 주석 그대로). 그래서 마이 화면(`my_screen.dart`) 제목이 `session.provider` 가 없어 "로그인됨"(`myLoggedIn`)이 되고, 부제는 프로필을 다 읽기 전까지 "계정 모름"·"불러오는 중".

## 부탁 (작은 수정)
1. 마이 화면 제목: `session.provider` 가 null 이면 **`profile.socialProvider`**(서버 `GET /me`)로 "Google로 로그인됨".
2. 부제는 지금도 `providerAccountText(profile)` 를 먼저 쓴다 — 프로필을 읽은 뒤에도 "계정 모름"이면 `/me` 가 실패한 것(그때 응답 코드를 알려 주면 서버를 본다).
- 서버 변경 없음.
