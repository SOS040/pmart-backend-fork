-- 필수 약관 동의 단계(AUTH_CONSENT, 2026-11-17 CU-1). 동의 자체는 customer-service 정본 — 여기에는 원인증 요청이 무엇을 물었는지와
-- 동의 완료(completions) 요청 키만 둔다(같은 키 재전송 = 응답 유실 재시도). 제공자 토큰·프로필은 저장하지 않는다.
-- 적용된 파일은 수정하지 않는다.
ALTER TABLE social_auth_request
    ADD COLUMN required_consents  varchar(500),     -- AUTH_CONSENT 일 때 반드시 동의할 항목 `TYPE:version` 을 쉼표로
    ADD COLUMN optional_consents  varchar(500),     -- 함께 보여 준 선택 항목 `TYPE:version`
    ADD COLUMN completion_key     varchar(128),     -- AUTH_CONSENT → AUTH_MEMBER 로 끝낸 completions 의 Idempotency-Key
    ADD COLUMN completed_at       timestamptz;

-- 처음 회원(AUTH_MEMBER)이 된 시각. 없으면 가입을 마치지 않은 계정(동의 단계에서 나감) — 다음 로그인도 가입(newAccount)으로 본다.
ALTER TABLE customer_account ADD COLUMN member_since timestamptz;
-- 지금까지는 동의 단계가 꺼져 있어(require-consent=false) 모든 계정이 만들어질 때 바로 회원이 됐다
UPDATE customer_account SET member_since = created_at WHERE member_since IS NULL;
