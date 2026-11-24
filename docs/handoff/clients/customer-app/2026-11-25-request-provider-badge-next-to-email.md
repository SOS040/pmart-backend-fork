# 요청 — 마이 화면 계정 줄에 가입 방식 배지(이메일 옆)

보낸 영역: platform
받는 앱: customer-app
작성: pmart-backend, 2026-11-25 — 사용자 "앱에서 가입 방식 배지를 이메일 옆에 같이 보여 주면 돼요 → 이것도 해줘"
상태: 요청(앱 작업). 백엔드 변경 없음

## 계기
- 사용자: "기존 구글 계정으로 이력이 있는 사람이 네이버로 소셜 로그인하니까 구글 계정으로 보였어".
- 백엔드 확인(2026-11-25): 계정은 섞이지 않았다 — 11-24 09:55 새 네이버 계정(customer `socialProvider` = NAVER). 네이버 프로필 API 가 준 이메일이 **gmail.com 주소**(네이버에 등록한 연락처 이메일)라 마이 화면 계정 줄 "이름 · ○○@gmail.com" 이 구글 계정처럼 보였다.

## 지금 앱(읽어 본 곳)
- `apps/customer_app/lib/features/orders/presentation/my_screen.dart` 205~225줄: 위 줄 "○○로 로그인" 은 `session.provider` 만 본다 — 앱을 다시 켜서 복원한 세션은 provider 가 null 이라 "로그인됨" 으로만 나온다. 아래 줄은 `providerAccountText(profile)`(이름 · 이메일).
- `my_info_screen.dart` 305줄은 이미 `profile?.socialProvider ?? session.provider` 를 쓴다.

## 요청
- R1 마이 화면 계정 줄의 이메일 옆에 **가입 방식 배지**(카카오·네이버·Google·Apple 아이콘 또는 짧은 이름) — 값은 `GET /me` 의 `socialProvider`(customer 계약 `Profile.socialProvider`, 가입한 제공자) 우선, 없으면 `session.provider`. 둘 다 없으면 배지 없음.
- R2 위 줄 "○○로 로그인" 도 같은 값(`profile.socialProvider ?? session.provider`)으로 — 복원한 세션에서도 제공자가 보이게.
- 이메일은 그대로(제공자가 준 연락처 이메일 — 네이버는 gmail 등 다른 주소일 수 있다). 다른 화면(내 정보)과 같은 배지 모양이면 좋다.
