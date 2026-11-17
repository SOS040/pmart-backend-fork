-- 반품 추가 결정(CL-1, 2026-11-17 사용자 "추천대로" 1~9). 흐름 정본 docs/business/flows/return-exchange-refund.md §6.
--  · 회수 시도 전체 3번(자동 2 + 상담원 1), 라이더·우리 잘못(RIDER_INCIDENT)은 횟수에서 빼고 바로 다시 요청 → 실패 종류를 회수 요청 행에
--  · 반품 전체 기한 접수 + 7일(pmart.claim.return-deadline) — 회수가 안 되면 자동 취소 → 클레임에 기한
--  · 회수 실패로 취소할 때 낸 회수비: 고객 사유 돌려주지 않음, 우리 잘못 돌려줌, 파손·기타 상담원 판단 → 회수비 행에 결정·환불 의도

-- 반품 전체 기한(접수 때 정함). 이 마이그레이션 전에 접수된 반품은 사용자 결정 값(7일)으로 채운다
ALTER TABLE claim ADD COLUMN return_deadline_at timestamptz;
UPDATE claim SET return_deadline_at = opened_at + interval '7 days' WHERE type = 'RETURN';
CREATE INDEX ix_claim_return_deadline ON claim (return_deadline_at)
    WHERE type = 'RETURN' AND state IN ('FEE_PENDING', 'IN_PROGRESS', 'RECOVERY_FAILED');

-- 회수 실패 종류: CUSTOMER(고객 사유 — 부재·연락 두절·주소 오류·거부) / OURS(RIDER_INCIDENT — 횟수에서 뺌) / OTHER(파손·기타 등)
ALTER TABLE return_pickup ADD COLUMN failure_kind varchar(12);
UPDATE return_pickup
   SET failure_kind = CASE
         WHEN failure_reason IN ('CUSTOMER_ABSENT', 'UNREACHABLE', 'ADDRESS_ERROR', 'REFUSED') THEN 'CUSTOMER'
         WHEN failure_reason = 'RIDER_INCIDENT' THEN 'OURS'
         ELSE 'OTHER'
       END
 WHERE state = 'FAILED';

-- 반품 취소 때 낸 회수비를 돌려주나(REFUND / KEEP — 결제된 회수비만). 돌려주면 환불 의도(reason RETURN_FEE, 회수비 결제 시도의 전액 취소)
ALTER TABLE return_fee ADD COLUMN refund_decision  varchar(8);
ALTER TABLE return_fee ADD COLUMN refund_intent_id varchar(64);
