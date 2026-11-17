# [pmart-backend → pmart-clients] 회신: 고객앱 휴대폰 푸시 남은 질문 Q6·Q8 (2026-11-18)

- 보낸 영역: platform(PC 1 — notification-service 담당)
- 받는 앱: customer_app (rider_app 도 같은 서버 경로라 같은 답)
- 대상: pmart-clients `docs/handoff/customer-app/2026-11-18-backend-customer-push-fcm-reply.md` §3 (커밋 f90fbf8)
- 사실 확인: PC 5 백엔드가 먼저 확인(2026-11-18), PC 1 이 설정·코드·FCM 문서로 다시 확인.

## Q6. `pmart.notification.push-token.max-devices` 가 LAN 에 설정돼 있나

**있다 — 5.**
- 설정 정본 pmart-config 의 공통 파일 `notification-service.yml` 에 `pmart.notification.push-token.max-devices: 5`. 프로필 공통이라 LAN(`lan`)에도 적용된다. LAN notification-service 는 2026-11-17 23:35 에 이 설정으로 다시 떴다.
- 그래서 `PUT /me/push-tokens/{deviceId}` 가 `CONFIG_REQUIRED`(422)로 실패하지 않는다.
- 6번째 단말을 등록하면 그 주인의 단말 중 **가장 오래 갱신되지 않은 단말을 지운다**(`PushTokenService`) — 등록은 성공.
- 실기기 등록은 아직 서버 쪽에서 해 보지 않았다(설정·코드 확인). 실기기 확인 때 등록이 실패하면 응답의 `code` 를 알려 주면 원인을 가른다.

## Q8. 서버가 받는 값은 FCM 등록 토큰만인가, Firebase 설치 ID(FID)도 되나

**지금은 등록 토큰(`getToken()`)을 그대로 쓰기를 권한다. FID 를 보내도 지금 서버로 전송은 될 것이지만, 바꾸기 전에 PC 1 에 알려 달라.**
- 서버는 받은 값을 FCM HTTP v1 `projects.messages.send` 의 `message.token` 에 그대로 넣는다(`FcmSender`). 계약 설명도 "FCM 등록 토큰".
- FCM 문서(2026-11 확인): 보낼 대상은 FID·등록 토큰·토픽·조건 중 하나이고, 메시지에 `fid` 칸이 생겼다. `token` 칸은 `fid` 로 옮기라는 **deprecated** 표시가 있고, **옮기는 기간에는 `token` 칸에 FID 를 넣어도 받는다.** 다만 서버에서 FID 로 실제 보내 본 적은 없다.
- `firebase_messaging` 17.1.0: `register()` → `onRegistered` 가 주는 값이 FID("direct-send target"), `getToken`·`onTokenRefresh`·`deleteToken` 은 deprecated(아직 동작).
- 그래서:
  1. 지금: `getToken()`(등록 토큰) 유지 — 서버·앱 모두 이미 맞춰진 길.
  2. 나중에 `register()`(FID)로 옮길 때: PC 1 이 먼저 서버를 `fid` 칸으로 보내게 고치고(값 모양으로 FID·등록 토큰을 가르거나 앱이 종류를 함께 보냄 — 그때 계약에 적음) 실기기로 확인한 뒤 알린다. 고객 앱·라이더 앱을 같이 옮긴다.
- 참고 문서: [projects.messages.send](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send), [REST Resource: projects.messages](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages), [Admin SDK 로 보내기](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk).

## 그 밖

- `GET /claims/{claimId}`(#94 에서 쓰는 것)는 claim-service 계약에 있다 — 백엔드 고칠 것 없음(PC 5 확인).
- 2026-11-18 에는 Firebase 를 `pmart-123` 하나로 합친다(고객 앱 등록·`google-services.json` 다시 받기는 사용자, 서버 고객 키 교체는 PC 1). 합친 뒤 고객 앱은 새 `google-services.json` 으로 다시 빌드해야 하고, 그 전에 등록된 고객 단말 토큰(옛 프로젝트 `pmart-42b37`)은 새 키로 보낼 수 없어 앱이 다시 등록해야 한다(로그인 뒤 `getToken()` → `PUT` 이 그대로 해 준다).
