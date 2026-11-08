# approval-service 에 대한 요청 (MFC 쪽에서)

## AP-1. MFC 승인 번호(approvalRef) 실제 검증 (2026-11-09 기록)

- **요청한 곳**: mfc-inventory(실사 조정·폐기 실행), mfc-quality(격리 해제·폐기), mfc-capacity(운영 제한 BO-08).
- **지금(임시)**: 세 서비스의 `ApprovalRefs` 가 설정 `pmart.approval-ref.mode: RECORD_ONLY` 일 때만 번호를 **확인 없이 기록**한다. 이 값은 LAN·local 설정에만 있다(`deploy/lan/.config-repo/application-lan.yml`, `application-local.yml`). 설정이 없으면 CONFIG_REQUIRED 로 막히므로 **승인 연동 전에는 운영에서 쓸 수 없다**.
- **approval-service 가 할 것**
  1. MFC 승인 유형 추가(BO-15 재고 조정·폐기, BO-08 운영 제한). 지금 `ApprovalRules.targetType` 은 PRODUCT·PRICE·DISPLAY·CENTER_MASTER·SERVICEABILITY·INITIAL_STOCK 만.
  2. 승인 번호 검증 수단 — 내부 API(계약 `approval-service.yaml` 에 없음, InternalController 501) 또는 사건 중 하나로 정한다.
- **요청한 쪽에서 바꿀 것**: `ApprovalRefs` 를 실제 검증으로 바꾸고 RECORD_ONLY 를 LAN 시험용으로만 남긴다.
- **사용자 결정**: Q-11 "위험 추가 승인(금액·수량 기준)" 은 기준이 정해지면(`docs/business/flows/pending-user-decisions.md`).
- 근거: `services/mfc-*-service/.../application/ApprovalRefs.java`, `docs/business/flows/mfc-defaults-status.md` #8.
