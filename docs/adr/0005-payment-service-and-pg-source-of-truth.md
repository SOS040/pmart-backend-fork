# ADR-0005 payment-service는 내부 서비스로 두되 거래 사실의 정본은 PG(토스페이먼츠)다

- 상태: 제안 (2026-10-23)
- 관련: CLAUDE.md L43 `payment-service`, OMS 7·10·A.1.6·A.2.2, Commerce 11장·§1.11, BackOffice BO-13

## 배경
OMS 문서는 Payment/PG를 "8개 분류에 추가되는 외부 연계 참여자"로, Commerce 문서는 "서버 결제 처리는 OMS 주관, 독립 결제 서비스 배포 미확정"으로 쓴다. CLAUDE.md는 `payment-service`(결제·승인 취소·환불 실거래·콜백·PG 대사)를 내부 서비스로 확정했다.

## 결정
- `payment-service`는 토스페이먼츠 어댑터이자 **결제 시도·PG 거래·취소/환불 거래 원장**의 정본이다. "추가 환불 가능 한도 = 승인액 − 성공 누적 − 불명 점유"를 계산하고 초과 요청을 거절한다.
- **실제 승인·취소 사실의 정본은 PG**다. 결제창 복귀·콜백 수신·Commerce 성공 표시만으로 승인을 확정하지 않고 원거래 조회로 대사한다.
- 환불 **의도·금액 산정**은 `claim-service`/`order-service`. payment는 금액 근거를 검증하지 않고 한도·멱등·원거래 적격만 검증한다.
- BackOffice는 payment에 독자 PG 명령을 보내지 않는다(BO-13). 모든 PG 명령은 order-orchestration 또는 claim 경유.
- 주류 인증은 토스페이먼츠 결제 인증 결과 확인으로 통합(자체 성인인증·현장 신분증 없음).

## 결과
- PG 비밀·카드 정보는 payment 외부로 나가지 않는다(로그·이벤트 금지).
- 결과 불명 절차(1분×5회→상신→15분)는 payment가 `PaymentResultUnknown` 이벤트와 조회 API로 제공, 추적은 orchestration.
- 라이더 수행료·운송비 **지급**은 payment 범위가 아니다(회계 연계 `[결정 필요]`).
