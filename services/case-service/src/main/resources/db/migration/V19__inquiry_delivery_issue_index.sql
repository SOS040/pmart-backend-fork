-- 배송 신고 종류로 문의 거르기(2026-11-18 사용자 "A해줘" — 배송 지연 신고, 계약 0.23.0 GET /inquiries?deliveryIssue=).
-- 상담원이 지연 신고(DELAYED)만 최근 순으로 본다. 배송 신고가 있는 행만.
CREATE INDEX ix_cs_inquiry_delivery_issue ON cs_inquiry (delivery_issue, created_at DESC, inquiry_id DESC) WHERE delivery_issue IS NOT NULL;
