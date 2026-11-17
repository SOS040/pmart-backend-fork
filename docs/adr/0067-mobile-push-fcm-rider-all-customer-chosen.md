# ADR-0067 휴대폰 푸시(FCM) — 라이더 알림은 전부, 고객은 정한 종류만, 앱마다 Firebase 키

- 상태: 승인 (2026-11-17, 사용자 — 라이더 "발송 코드만 먼저, 키는 나중"(PC 5 #136) → 키 "응 해줘", 고객 "응 해줘"(추천 범위), Firebase 프로젝트 "고객앱용으로 만든 건데" → "오늘은 지금 구조로, 내일 프로젝트 하나에 앱 둘")
- 대체: ADR-0064 결정 1(채널은 앱 알림함만 — 푸시는 나중)·ADR-0066 결정 1(라이더도 푸시는 나중)의 "푸시는 나중" 부분. 알림함이 정본인 것은 그대로.
- 관련: notification-service `RiderPush`(PC 5)·`CustomerPush`·`FcmSender`, 계약 `notification-service.yaml` 0.4.1(설명만), 단말 토큰 `/me/push-tokens`(0.3.0, BR-09), 요청 `docs/handoff/clients/rider-app/2026-11-17-request-rider-push-fcm.md`.

## 결정
1. **알림함이 정본, 푸시는 같은 알림의 추가 채널.** 알림함에 새 알림이 저장된 트랜잭션이 **커밋된 뒤** 그 사람의 등록 단말마다 FCM HTTP v1 로 보낸다. 제목·본문은 알림함과 같고(개인정보 없음) data 에 알림 ID·종류·목적지. 실패는 기록만 하고 다시 보내지 않는다(알림 실패로 업무를 되돌리지 않음). 더는 쓸 수 없는 토큰(UNREGISTERED)은 지운다.
2. **라이더: 라이더 알림 전부.** 라이더 알림은 모두 처리할 일(지원센터 답장·도움요청 처리 끝·문의 답변·계정 제한·수행료 조정)이라 고르지 않는다.
3. **고객: 정한 종류만** — 배송 출발(`DELIVERY_STARTED`)·배송 완료(`DELIVERY_COMPLETED`)·결품 부분 취소(`SHORTAGE_CANCELLED`)·주문 취소(`ORDER_CANCELLED`)·환불 결과(`REFUND_SUCCEEDED`·`REFUND_DELAYED`), **오배송 다시 배송비 결제 필요(`REDELIVERY_PAYMENT_REQUIRED`)** — 2026-11-17 사용자 "넣고"로 추가: 30분 안에 내야 하고 안 내면 회수·(상품값 − 9,000원) 환불(ADR-0065)이라 알림함만으로는 놓친다. 주문 확정·상품 준비·예약 시간 변경·지연 가능·문의 답변·반품 회수 등은 알림함만. 목록은 설정 `pmart.notification.fcm.customer-templates`(설정 저장소) — 비면 고객 푸시 없음(기본값을 지어내지 않는다).
4. **키는 앱의 Firebase 프로젝트마다.** 단말 토큰은 그 앱이 받은 프로젝트의 키로만 보낼 수 있다. 라이더 `pmart.notification.fcm.service-account-json`(Firebase `pmart-123`), 고객 `pmart.notification.fcm.customer-service-account-json`(오늘은 `pmart-42b37`). 둘 다 설정 저장소 `{cipher}`, 원본은 `deploy/lan/.secrets`(git 제외). 키가 없는 쪽은 꺼짐(알림함만).
   - **내일(2026-11-18) 예정**: 프로젝트 하나(`pmart-123`)에 앱 둘로 합친다 — 고객 자리에 같은 `pmart-123` 키를 넣으면 된다(코드 변경 없음). 고객 앱 `google-services.json` 은 그 프로젝트에서 다시 받는다.
5. **Google 로그인과 무관.** 두 앱 모두 로그인 대상(웹 클라이언트 ID, `pmart-123`)을 빌드 값으로 직접 넘긴다 — `google-services.json` 을 넣어도 로그인 `aud` 는 그대로. 빌드 값을 빼면 안 된다.
6. 야간 발송 제한·수신 설정(끄기)·발송 결과 사건(`NotificationDelivered`·`Failed`)은 아직 없다 — 거래 안내라 끌 수 없는 것만 보낸다(ADR-0064 결정 3 그대로).

## 결과
- 라이더 앱(PC 5)·고객 앱(PC 1 클라이언트)은 Firebase 연결 + 로그인 뒤 `PUT /me/push-tokens/{deviceId}`, 로그아웃 때 `DELETE` 를 하면 푸시를 받는다.
- 서버 키를 바꿀 때: 새 키 → 설정 `{cipher}` → notification-service 재시작 → 콘솔에서 옛 키 삭제.
