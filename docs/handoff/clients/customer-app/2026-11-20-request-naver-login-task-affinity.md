# [pmart-backend → pmart-clients] 요청: 네이버 로그인 — MainActivity `android:taskAffinity=""` 빼기 (2026-11-20)

- 보낸 영역: platform(PC 1)
- 받는 앱: customer-app (같은 설정: rider-app·mfc-app — 라이더 앱도 네이버 로그인을 쓴다)
- 로그인 코드라 PC 1 에서 고친다(규칙 "로그인 코드는 PC 1 만").
- 근거: 2026-11-20 LAN 폰(SM-A536N) 연결 진단 — `docs/operations/2026-11-19-naver-oauth-lan-callback-diagnosis.md`

## 지금까지
1. 서버(auth 0.7.0, 2026-11-20 LAN 배포 1d3df48a): 네이버 콜백을 302 대신 "PMart 앱으로 돌아가기" 페이지로 바꿨다 — 302 로는 Chrome 이 앱을 열지 않았다.
2. 그 뒤 시도(09:46·09:48 세 번): Chrome 이 `com.pmart.customer://oauth/naver?…` 로 **앱을 연다**(RedirectUriReceiverActivity → AuthorizationManagementActivity). 그런데 바로
   `W/AppAuth: No stored state - unable to handle response` — 응답을 버린다. 로그인이 끝나지 않고, Custom Tab 에 남은 네이버 페이지가 일반 오류("접속자가 많거나 네트워크 불안정")를 보인다.

## 원인
`apps/customer_app/android/app/src/main/AndroidManifest.xml` MainActivity 의 `android:taskAffinity=""`(Flutter 새 템플릿 기본값).
로그인을 시작한 AuthorizationManagementActivity 는 MainActivity 의 task(affinity "")에 있는데, 리다이렉트를 받은 RedirectUriReceiverActivity 는 앱 기본 affinity task 에서 새 AuthorizationManagementActivity 를 띄운다 → 새 인스턴스에는 요청 정보(state)가 없어 "No stored state".

## 바꿔 주세요
- customer-app·rider-app·mfc-app MainActivity 에서 `android:taskAffinity=""` 줄 삭제(launchMode `singleTop` 은 그대로).
- 확인: 네이버 로그인 → 동의 → 앱으로 돌아와 `POST /api/v1/auth/social-logins`(provider NAVER)까지. PC 1 이 서버 기록으로 같이 본다(시각만 알려 주면 됨).
- 구글·카카오는 SDK 라 영향 없음(그대로 확인만).

## 상태
- 2026-11-20: 요청. 서버 쪽은 끝(콜백 페이지).

## 처리 — 2026-11-20 PC 1(사용자 "세 앱 다 고쳐줘")
- pmart-clients main dbee5438: customer_app·rider_app·mfc_app MainActivity 의 `android:taskAffinity=""` 삭제. mfc_app 의 flutter_web_auth_2 CallbackActivity 는 그대로.
- 11:01 시도도 같은 증상(콜백 200 뒤 social-logins 없음). 앱 다시 빌드·설치 뒤 네이버 로그인으로 확인 — 아직 안 함.
