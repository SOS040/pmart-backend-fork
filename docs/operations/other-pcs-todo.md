# 다른 PC 할 일 — PC 별 체크리스트 (2026-11-13 기준)

PC 1 이 아닌 PC(사람·Claude)가 **지금 해야 할 일**만 모은 목록. 방법은 `dev-on-other-pcs.md`, 영역·규칙은 `pc-split.md`.
끝낸 줄은 그 PC 가 PR 로 `[x]` 로 바꾸고 날짜를 적는다(자기 PC 절만 고친다 — 다른 절은 PC 1 이 고친다). 새 할 일이 생기면 PC 1 이 여기에 더한다.

## 모든 다른 PC 공통 (개발 시작 전)

- [ ] 저장소 셋을 같은 부모 폴더의 형제로 받기 — `pmart-backend`·`pmart-clients`·`pmart-mfc-simulation`(드라이브 무관, `dev-on-other-pcs.md` §0)
- [ ] gh 설치·로그인(두 줄 따로: `winget install --id GitHub.cli -e` → 새 터미널에서 `gh auth login`) → 포크 만들고 `fork` remote 추가
- [ ] 비밀번호 파일은 필요한 것만 **`deploy/lan/.secrets/`** 에(사용자가 복사). 저장소 루트 `.secrets/` 에 두지 않는다. `keycloak-admin-password` 는 받지 않는다
- [ ] `./gradlew --stop` 한 번 — Gradle 사용자 단위 제한(workers 2·Xmx2g, 2026-11-13 PC 2~5 적용)이 새 데몬부터 적용된다
- [ ] 루트 CA: PC 2~5 Windows 에는 설치돼 있음. PDA·폰 실기기에서 직원 로그인하려면 기기에도 설치

## PC 3 — mfc 영역 (GitHub YOO196)

- [x] 포크 → PR → PC 1 병합 경로 시험 — PR #1 병합 `965e7e6`(2026-11-13)
- [ ] **저장소 루트 `.secrets/` 사본 삭제**(PR #1 에서 발견. `deploy/lan/.secrets/` 쪽만 남긴다. 이제 `.gitignore` 가 막지만 사본은 지운다)
- [ ] `git pull origin main`(`.gitignore`·이 문서·dev-on-other-pcs 갱신 받기)
- [ ] `docs/mfc/HANDOVER.md` 읽기 — 시작 전 할 일(OR-Tools Docker 시험 등)·남은 일은 거기에
- [ ] 첫 MFC 작업 전: 작업자 명단 ↔ 인사(hr-service) 연결은 사용자 결정 3개 대기(`pc-split.md` §1-2) — 결정 전에는 손대지 않는다

## PC 5 — delivery 영역

- [ ] 공통 절 전부(gh·포크·`./gradlew --stop` — 2026-11-13 기준 PC 1 에서 확인한 기록 없음)
- [ ] 배송 쪽 직원 시험 계정은 없다 — 배송 관리 API 를 직원 토큰으로 시험해야 하면 PC 1 에 요청(인사팀 등록으로 만든다)
- [ ] LAN 서비스가 많은 PC 다(남은 메모리 약 5GB, 2026-11-13) — 빌드·시험 중 LAN 서비스 상태가 나빠지면 PC 1 에 알린다

## 클라이언트(pmart-clients) — 클라이언트를 맡은 PC

백엔드에서 회신을 기다리는 것(자세한 내용은 각 handoff):
- [ ] HTTPS 빌드 소스(vite.config 등) 커밋 — `docs/handoff/clients/2026-11-13-all-reply-https-builds-published-http-closed.md`
- [ ] 두 백오피스 브라우저 확인: `https://192.168.0.40`(백오피스)·`https://192.168.0.40:8444`(인사팀) 로그인·업무 화면 — 같은 문서
- [ ] 예전 운영자 로그인 코드 삭제 뒤 `gen:api` — `2026-11-13-clients-notice-auth-operator-login-removed.md`
- [ ] mfc_app: `SUBJECT_BLOCKED`(로그아웃·정지 즉시 401) 안내, **실기기 로그인 확인**(로그인 마무리 기준 4 — `pc-split.md` §1-1) — `2026-11-13-clients-reply-mfc-app-login-and-https-build.md`
- [ ] 권한 기간 "기한 없음" 표시 — `2026-11-13-clients-reply-hr-backoffice-deployed.md`
- 결과는 클라이언트 저장소 handoff 로 회신(백엔드는 읽기만).

## PC 2·PC 4 — 아직 영역 없음

- 지금 할 일 없음(LAN 서비스 실행 PC). 예정: rag(PC 2 추천), backoffice(로그인 마무리 뒤, PC 4 추천) — 사용자 결정 뒤 이 절에 할 일을 적는다(`pc-split.md` §1-1, `docs/rag/HANDOVER.md`).

## 사용자가 할 일 (PC 가 아니라 사람)

- [ ] 다른 PC 에 비밀번호 파일 복사(필요한 PC·파일만 — `dev-on-other-pcs.md` §2)
- [ ] 결정: rag 영역 PC·개발 PC 에서 ollama 쓰는 방법, backoffice 영역 경계, CSRF 언제 할지, MFC↔인사 3개
