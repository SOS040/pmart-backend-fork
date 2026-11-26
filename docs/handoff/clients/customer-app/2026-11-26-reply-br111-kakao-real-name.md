# 회신 — BR-111 카카오 가입 때 실명(name) 받기

보낸 영역: platform
받는 앱: customer-app
요청: pmart-clients `docs/handoff/customer-app/2026-11-26-backend-kakao-real-name.md`(R1~R3·Q1)
작성: pmart-backend PC 1, 2026-11-26 — 사용자 "응"(Q1 추천 = R1 먼저 넣어 두기)
상태: 구현·시험 통과(auth-service check 단위 22·통합 40). **LAN 배포 전**(배포되면 이 줄을 고친다)

## 요청서 진단 확인
- 서버 쪽 진단은 모두 맞았다 — 카카오는 `withProviderProfile` 이 이름을 일부러 비웠고, `KakaoUnlinkClient.profile` 은 닉네임·이메일·사진만 읽었다.

## 바꾼 것
- **R1**: auth-service 가 카카오 사용자 정보(`GET /v2/user/me`, Admin 키)의 `kakao_account.name`(동의항목 "이름")을 읽어, 토큰에 이름이 없으면 로그인 사건 `CustomerAuthenticated.name` 으로 싣는다 → customer `Profile.name`(nameSource PROVIDER). 앞뒤 공백 제거, 100자 넘거나 제어 문자가 있으면 버린다(네이버 이름과 같은 규칙). **동의 안 했거나 항목이 없으면 지금처럼 비움 — 닉네임으로 채우지 않는다.**
- **R2**: 그대로 — 고객이 넣은 이름(nameSource CUSTOMER)은 로그인이 덮지 않는다(BR-107).
- **R3**: 계약 설명 — customer `Profile.name`, 사건 `CustomerAuthenticated.name`.

## 앱
- 변경 없음. 카카오 콘솔에서 "이름" 동의가 켜지기 전에는 이름 칸이 지금처럼 비어 고객이 입력한다. 켜진 뒤 가입하거나 다시 로그인하면 이름이 들어온다(늦게 온 이름을 빈 칸에 넣는 앱 고침과 함께 동작).
- 요청서의 앱 고침(`my_info_screen.dart`)은 2026-11-26 확인 때 pmart-clients 작업 폴더에서 **커밋 전**이었다.

## 사용자 할 일(콘솔)
- 카카오 디벨로퍼스 동의항목 **"이름(name)"** 켜기 — 비즈 앱 전환 + 권한 신청(검수). 켜지면 백엔드 변경 없이 바로 들어온다.
