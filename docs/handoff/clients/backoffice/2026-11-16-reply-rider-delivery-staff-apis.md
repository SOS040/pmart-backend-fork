# [pmart-backend → pmart-clients] 회신: 라이더·배송 직원 화면 — refs 질문 답 + 빌드 올림 (2026-11-16)

- 보낸 영역: platform
- 원 문서: pmart-clients `docs/handoff/backoffice/2026-11-16-backend-rider-delivery-staff-apis-done.md` §3 질문, `web-common/2026-11-16-backend-session-roles-centers-done.md` §2(빌드)

## 1. 질문 — 사건 `refs` 에 `incidentId` 가 늘 오나
- **RIDER_HELP·DELIVERY_INCIDENT 는 늘 온다.** 두 사건 모두 delivery-execution 의 도움요청 기록(라이더 도움요청, 전달 실패 등록)에서 열리고, 그 사건(`DeliveryIncidentRaised`)에 incidentId 가 없으면 case-service 가 사건을 만들지 않는다. 형식 `<deliveryId>:<requestId>`, 같이 `deliveryId` 도 늘 있다.
- **RIDER_LOCATION_STALE·MISDELIVERY 에는 없다** — 도움요청이 아니다(위치 끊김은 riderId·deliveryId, 오배송은 deliveryId·riderId).
- **requestId 를 따로 싣는다(case 0.4.1, 오늘 배포)**: 새로 열리는 도움요청·전달 실패 사건의 `refs` 에 `{refType: "requestId", refId: …}` 가 더해진다. **그 전에 열린 사건에는 없으니** 지금처럼 incidentId 에서 `deliveryId + ":"` 를 떼어 쓰는 길을 남겨 둔다.

## 2. 빌드 올림
| 앱 | 주소 | 진입 파일 |
|---|---|---|
| backoffice | https://192.168.0.40 | `index-BGISi3dQ.js` (f50025c — 라이더·배송 직원 화면 포함) |

- hr-backoffice·mfc-backoffice 는 바뀌지 않아 그대로(`index-Bkg8rNsZ.js`·`index-ChSinewe.js`).

## 3. 참고
- 남은 백오피스 요청은 개인정보 열람 기록 메뉴(`backoffice/2026-11-16-notice-pii-access-log-api.md`) 하나.
- 리뷰(customer-app 알림 `2026-11-16-notice-review-service.md`)·고객 알림함(`2026-11-16-notice-notification-inbox.md`)은 고객 앱 쪽 — 백오피스 화면은 없다(리뷰 숨김·신고는 사용자 결정으로 아직 없음).
