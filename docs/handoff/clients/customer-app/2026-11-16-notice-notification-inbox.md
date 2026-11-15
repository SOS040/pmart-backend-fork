# [pmart-backend → pmart-clients] 알림: 고객 알림함 서버 구현 — notification 0.2.0 (2026-11-16)

- 보낸 영역: platform
- 받는 앱: customer_app (`lib/features/notifications` — 지금 목 데이터)
- 계약: `contracts/openapi/notification-service.yaml` **0.2.0**, 결정 ADR-0064. LAN 배포 상태는 §4.

## 1. 그대로 쓰는 것
- `GET /me/notifications?unreadOnly=&category=&size=&cursor=` → `items[]`·`unreadCount`·`page.nextCursor`(최근 순).
- `GET /me/notifications/{id}`, `POST /me/notifications/{id}/reads`(헤더 `Idempotency-Key`·`X-Source-System`, 이미 읽었으면 200 그대로).
- `GET /me/notification-settings` — 지금은 ORDER·DELIVERY·REFUND·CASE 네 줄, 모두 `IN_APP`·켜짐·`locked: true`. `marketingConsent`·`osPermission` 은 UNKNOWN.
- `POST /me/notification-setting-changes` — 끄기는 422 `TRANSACTIONAL_CANNOT_DISABLE`, 앱 알림함이 아닌 채널(푸시·알림톡·문자)·마케팅은 422 `CHANNEL_NOT_APPROVED`. **설정 화면은 "모든 안내는 끌 수 없음" 으로 보이면 된다.** 변경 결과 조회(`…/{requestId}`)는 아직 501.

## 2. 쌓이는 알림(고객만, 휴대폰 푸시 없음 — 앱을 열어야 보인다)
| 일 | category | title | 목적지 |
|---|---|---|---|
| 주문 확정 | ORDER | 주문이 확정되었어요(결제 금액·도착 예정) | ORDER |
| 상품 준비 시작 | ORDER | 상품 준비를 시작했어요 | ORDER |
| 품절 부분 취소 | ORDER | 일부 상품이 품절되어 취소되었어요(상품명) | ORDER |
| 주문 취소 | ORDER | 주문이 취소되었어요 / 품절로 주문이 취소되었어요 | ORDER |
| 배송 출발 | DELIVERY | 배송이 출발했어요 | ORDER |
| 배송 완료 | DELIVERY | 배송이 완료되었어요(리뷰 14일 안내) | ORDER |
| 환불 완료·지연 | REFUND | 환불이 완료되었어요(금액) / 환불 처리가 늦어지고 있어요 | REFUND(ref = claimId) |
| 고객센터 답변 | CASE | 문의에 답변이 등록되었어요 | **INQUIRY**(ref = inquiryId) |

- **새 목적지 `INQUIRY`**: 고객센터 문의 상세(case `GET /inquiries/{inquiryId}`)로 보낸다. 앱 `NoticeDestinationType` 에 없으면 지금처럼 이동하지 않는다.
- 본문은 그때의 사실이다(최신 상태는 목적지에서). 취소·품절 알림은 환불을 끝났다고 쓰지 않는다 — 환불은 결과가 나온 뒤 따로 온다.
- 분할 배송이면 배송마다 출발·완료 알림이 갈 수 있다(지금 주문은 출고 1개).

## 3. 주의
- 앱 아이콘 배지·알림함 점은 `unreadCount` 로. 푸시가 없으니 앱을 열 때·포그라운드로 올 때 다시 읽는다.
- 라이더 토큰으로 부르면 빈 알림함(라이더 알림은 아직 없음), 직원 토큰은 403.

## 4. LAN
- 배포 전(이 알림 시점). 배포하면 이 절에 적는다.
