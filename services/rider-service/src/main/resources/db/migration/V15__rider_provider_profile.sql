-- 소셜 이름·닉네임 사본(2026-11-25 — 사용자 "라이더쪽에도 지금 고객앱 소셜로그인 기능 구조처럼" "이름 닉네임 먼저해줘"). 적용된 파일은 수정하지 않는다.
-- rider-auth RiderAuthenticated(platform.auth.v1)로 채운다. 가입 전에도 있다(가입 화면 이름 미리 채우기) — rider 행과 FK 없음.
-- 라이더 실명은 그대로 rider.name(가입 때 라이더가 넣은 값)이 기준. 개인정보 — 로그 금지, 탈퇴 때 지움.
CREATE TABLE rider_provider_profile (
    rider_id          varchar(64) PRIMARY KEY,
    provider          varchar(16) NOT NULL,
    name              varchar(100),
    provider_nickname varchar(100),
    authenticated_at  timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);
