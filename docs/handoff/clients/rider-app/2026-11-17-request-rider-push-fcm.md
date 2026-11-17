# [pmart-backend → pmart-clients] 요청: 라이더 앱 휴대폰 푸시(FCM) 연결 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 받는 영역: rider-app(PC 5)
- 근거: 사용자 2026-11-17 — Firebase 프로젝트 연결("라이더쪽 확인해줘", "응 해줘"). 앞 회신 `2026-11-17-reply-rider-notifications.md`(알림함 — "푸시는 키가 생기면")의 다음 단계.

## 백엔드 상태 (2026-11-17 끝, LAN)

- notification-service **라이더 푸시 켜짐**: Firebase 프로젝트 **`pmart-123`** 서비스 계정 키(pmart-config `{cipher}` 370cac1), PC 4 재시작 — 키 읽기 확인(시작 로그에 "FCM 키 없음"·"읽지 못함" 없음). 알림함에 쌓이는 라이더 알림을 등록된 휴대폰마다 FCM HTTP v1 로도 보낸다. 무효 토큰(UNREGISTERED)은 서버가 지운다.
- 단말 토큰 API(계약 notification-service, 사용자 종류 CUSTOMER·RIDER — 이미 배포):
  - `PUT /api/v1/me/push-tokens/{deviceId}` 본문 `{ "token": "<FCM 토큰>", "platform": "ANDROID" }`, 헤더 `Idempotency-Key`·`X-Source-System` — 같은 단말은 토큰 교체(멱등). 한 사람 단말 5대까지(넘으면 가장 오래된 것 지움).
  - `DELETE /api/v1/me/push-tokens/{deviceId}` — 로그아웃·알림 끄기(없어도 204).
  - `GET /api/v1/me/push-tokens` — 내 단말 목록(토큰 값은 안 돌려줌, 점검용).
- 아직 **보낼 휴대폰이 없다** — 라이더 앱이 토큰을 등록해야 실제로 간다.

## 라이더 앱이 할 것

1. **`google-services.json`**: `apps/rider_app/android/app/google-services.json`(이름 정확히 — `.gitignore` 의 `google-services.json` 으로 커밋 안 됨). 프로젝트 `pmart-123`, 패키지 `com.pmart.rider_app` 확인함. **PC 1 에만 있다 — 사용자가 PC 5 로 복사**(git 으로 오지 않는다).
2. Gradle: `android/settings.gradle.kts` plugins 에 `id("com.google.gms.google-services") version "<최신 4.x>" apply false`, `android/app/build.gradle.kts` plugins 에 `id("com.google.gms.google-services")`.
3. 패키지 `firebase_core`·`firebase_messaging`(버전은 그때 최신). `main()` 에서 `await Firebase.initializeApp();`(Android 만 — google-services.json 으로 충분, FlutterFire CLI 불필요).
4. 알림 권한(Android 13+): `FirebaseMessaging.instance.requestPermission()`.
5. 로그인 뒤 `getToken()` → `PUT /me/push-tokens/{deviceId}`(deviceId = 앱이 이미 쓰는 단말 ID). `onTokenRefresh` 때 다시 PUT. 로그아웃 때 `DELETE`. **토큰 값은 로그·오류 문구에 남기지 않는다.**
6. 받은 푸시 누르면 알림함(또는 그 배송) 화면으로 — 푸시 `data` 에 알림 ID 가 실린다(서버 `RiderPush`).
7. 시험: 디버그에서 Firebase 콘솔 → Messaging → "테스트 메시지 보내기"로 휴대폰 수신 먼저 확인 → 로그인 뒤 `GET /me/push-tokens` 에 단말이 보이는지 → 배차 제안 등 라이더 알림이 생기면 푸시가 오는지.

- 로그인 코드(`apps/rider_app` 로그인)는 PC 5 그대로(라이더 예외 — `pc-split.md` §1-3). 공용 `packages/pmart_core` 토큰 코드는 PC 1 — 고칠 일이 생기면 요청.

## 회신

이 폴더 회신으로: 커밋, 실기기에서 푸시 받은 결과(어떤 알림으로 시험했는지).
