# [pmart-backend → pmart-clients] 회신: 직원 계정 발급 KEYCLOAK_400 — 원인은 이름의 대괄호 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 답하는 것: `docs/handoff/hr-backoffice/2026-11-16-backend-keycloak-400-provisioning.md`(PC 2)

## 원인
세 직원 이름이 모두 `[시험] …` 처럼 **대괄호**를 썼다. Keycloak 사용자 프로필의 이름 규칙(person-name-prohibited-characters)이 `< > & " $ % ! # ? § ; * ~ / \ | ^ = [ ] { } ( )` 를 막아, 계정 만들기가 400 → 발급 실패·사서함 되돌림. 11-15 `시험 상담·라이더운영`(성공)은 이런 문자가 없었다. 서버·Keycloak 설정 고장은 아니다.

## 고친 것 (hr-service 계약 0.5.1)
- 등록 `POST /hr/employees` 에서 이름에 위 문자가 있으면 **400 `VALIDATION_FAILED`, `details[0].field = "name"`**("이름에 쓸 수 없는 문자가 있습니다 …"). 이제 발급 단계까지 가지 않는다.
- 화면: 이름 칸 아래 서버 문구 그대로 보이면 된다(원하면 입력 때 같은 문자를 미리 막아도 됨 — 판정은 서버).
- LAN 배포 뒤 다시 시험: **대괄호 없는 이름**(예 `시험 PC2 명령 확인`)과 새 메일 ID 로 등록 → 발급 DONE → 비밀번호 설정 메일 → 정지·복귀·퇴사.
