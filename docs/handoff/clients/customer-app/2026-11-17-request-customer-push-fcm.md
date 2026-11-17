# [pmart-backend → pmart-clients] 요청: 고객 앱 휴대폰 푸시(FCM) 연결 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 받는 영역: customer-app(PC 1 클라이언트 세션)
- 근거: 사용자 2026-11-17 — 고객 푸시 "응 해줘"(추천 범위), Firebase "고객앱용으로 만든 건데" → "오늘은 지금 구조로, 내일 프로젝트 하나에 앱 둘". ADR-0067.

## 백엔드 (notification-service, 계약 0.4.1 — 경로 변경 없음)

- 고객 알림함에 저장되는 알림 중 **정한 종류만** 등록 단말로도 보낸다: 배송 출발(`DELIVERY_STARTED`)·배송 완료(`DELIVERY_COMPLETED`)·결품 부분 취소(`SHORTAGE_CANCELLED`)·주문 취소(`ORDER_CANCELLED`)·환불 완료(`REFUND_SUCCEEDED`)·환불 늦어짐(`REFUND_DELAYED`), **오배송 다시 배송비 결제 필요(`REDELIVERY_PAYMENT_REQUIRED`, 2026-11-17 추가 — 30분 기한)**. 이 푸시를 누르면 알림함과 같이 목적지 ORDER(orderId) → 주문 상세의 다시 배송비 결제 화면(요청 `2026-11-16-request-redelivery-payment.md`)으로. 나머지(주문 확정·상품 준비·문의 답변 등)는 알림함만.
- 푸시 제목·본문 = 알림함과 같음(개인정보 없음). `data`: `notificationId`·`templateKey`·`destinationType`(ORDER·REFUND …)·`destinationRef` — 누르면 그 화면(또는 알림함)으로.
- 단말 토큰 API(0.3.0, 이미 배포 — 고객 토큰 가능): `PUT /api/v1/me/push-tokens/{deviceId}` 본문 `{ "token": "<FCM 토큰>", "platform": "ANDROID" }`(헤더 `Idempotency-Key`·`X-Source-System`), 로그아웃 때 `DELETE /api/v1/me/push-tokens/{deviceId}`, 점검 `GET /api/v1/me/push-tokens`. 한 사람 단말 5대까지.
- LAN 배포 상태는 백엔드 회신·일지로 알린다(이 문서 작성 때는 배포 전).

## 고객 앱이 할 것

1. **Firebase 프로젝트 주의**: 오늘(11-17) 고객 앱은 `pmart-42b37`, **내일(11-18) `pmart-123` 하나로 합칠 예정**(사용자). `google-services.json` 은 그때 쓰는 프로젝트에서 받은 것(패키지 `com.pmart.customer_app`)을 `apps/customer_app/android/app/google-services.json` 에 — 서버 고객 키와 같은 프로젝트여야 한다. git 에 넣지 않는다(`.gitignore` 의 `google-services.json`). 프로젝트 ID 를 코드에 적지 않는다(파일만 바꾸면 되게).
2. Gradle: `android/settings.gradle.kts` plugins `id("com.google.gms.google-services") version "<최신 4.x>" apply false`, `android/app/build.gradle.kts` plugins `id("com.google.gms.google-services")`.
3. `firebase_core`·`firebase_messaging`(그때 최신). `main()` 에서 `await Firebase.initializeApp();`(Android 만 — FlutterFire CLI 불필요). 알림 권한(Android 13+) `requestPermission()`.
4. 로그인 뒤 `getToken()` → `PUT /me/push-tokens/{deviceId}`, `onTokenRefresh` 때 다시, 로그아웃 때 `DELETE`. 토큰 값은 로그·오류 문구에 남기지 않는다.
5. **Google 로그인 그대로**: 로그인 대상은 빌드 값 `googleServerClientId`(`pmart-123` 웹 클라이언트)로 넘긴다 — `google-services.json` 을 넣어도 바뀌지 않는다. **빌드 값을 빼면** 새 json 의 클라이언트를 쓰게 되어 로그인이 401 이 될 수 있으니 지금처럼 꼭 넣는다.
6. 시험: Firebase 콘솔 "테스트 메시지"로 수신 → 로그인 뒤 `GET /me/push-tokens` 에 단말 → 주문 하나를 취소하거나 배송 출발이 되면 푸시가 오는지, 주문 확정은 알림함에만 있는지. 로그인이 그대로 되는지도 같이.

- 로그인·세션 코드(`apps/customer_app/lib/features/auth`)는 PC 1 만 — 같은 PC 라 문제없지만 토큰 등록은 로그인 코드 밖(앱 시작·로그인 완료 뒤 훅)에 두는 것을 추천.

## 회신

이 폴더 회신으로: 커밋, 실기기에서 받은 푸시(어떤 알림), 로그인 확인.
