# 회신: 클라이언트 할 일 3건 받음 · 새 웹 빌드 · handoff 폴더 제안

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-13
- 대상: all (backoffice·hr-backoffice·mfc-app·web-common)
- 답하는 것: pmart-clients `docs/handoff/2026-11-13-backend-client-todo-done.md`(ca2ecf3 — 클라이언트 작업 폴더에서는 `all/` 로 옮기는 중), `docs/handoff/all/2026-11-13-backend-handoff-folders.md`(클라이언트 작업 폴더에서 읽음 — 커밋 전)

## 1. 할 일 3건 — 받음

| 항목 | 백엔드 처리 |
|---|---|
| 옛 운영자 로그인 코드 삭제 + `gen:api`(9d5c372) | 받음. `other-pcs-todo.md` 클라이언트 절 체크 |
| 권한 기간 "기한 없음" | 받음. 같은 절 체크 |
| mfc_app `SUBJECT_BLOCKED` 안내 | 받음. PC 3 절 mfc_app 줄에 "안내 끝 — 실기기 확인만 남음" |
| HTTPS 빌드 소스 | 앞서 받음(13608a6) |

- 남은 것: 두 HTTPS 주소의 브라우저 확인은 사용자가 11-13 "HTTPS 전환했어"로 확인했다(그 빌드 기준). 이번 새 빌드는 올린 뒤 다시 봐 달라. mfc_app 실기기는 PC 3.

## 2. 새 웹 빌드 두 개

- 백오피스 `index-C_IVVcnv.js`, 인사팀 `index-B-0VeOMD.js` — **올리는 것은 사용자 확인 뒤**(PC 4 배포). 올리면 이 파일 아래에 결과(올린 진입 파일 이름)를 덧붙인다.
- 올리기 전에 지금 PC 4 에 떠 있는 index.html 과 비교한다(11-13 덮어쓰기 사고 뒤 정한 순서).

## 3. `SUBJECT_BLOCKED` 구별(§4) — 사실 확인만

- 맞다. 401 `SUBJECT_BLOCKED` 는 정지·퇴사뿐 아니라 끝난 세션(로그아웃·모든 기기 로그아웃·refresh 재사용)의 토큰에도 나온다. 게이트웨이는 사유를 응답에 싣지 않는다.
- 지금은 바꾸지 않는다(요청 없음). 구별이 필요해지면 그때 정한다 — 예: 갱신 응답에서 정지·퇴사는 403 `ACCOUNT_NOT_AVAILABLE`, 끝난 세션은 401 `TOKEN_INVALID`/`TOKEN_REUSED`.

## 4. handoff 폴더 제안(§3) — 결정 대기

- 백엔드 `docs/handoff/clients/` 를 앱 영역 폴더(`customer-app/`·`mfc-app/`·`rider-app/`·`backoffice/`·`hr-backoffice/`·`web-common/`·`all/`·`archive/`)로 나눌지는 **사용자가 정한다**. 정해지면 옮기고 알린다(옮긴 파일 목록 포함).
- 11-13 오후 백엔드는 "앱 폴더는 나누지 않고 끝난 것만 `archive/`"로 했었다(`2026-11-13-all-notice-handoff-archive.md`). 클라이언트 쪽 폴더가 나뉘었으니 맞추는 것이 낫다고 본다 — 사용자에게 올렸다.
- 클라이언트가 옮긴 문서를 가리키는 백엔드 링크(`pmart-clients/docs/handoff/<파일>`)는 클라이언트 쪽 옮기기가 커밋되면 고친다.

## 5. 함께 알림 — 이미 보낸 요청

- `2026-11-13-web-common-request-silent-login.md` — 백오피스를 열 때 조용히 로그인 확인(employee-auth-service 0.2.0 `silent`). web/core 에 부탁.
