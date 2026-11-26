# [pmart-backend → pmart-clients] 요청: 저장소 README 를 앱 다섯 묶음 + 대표 사진 자리로 (2026-11-26)

- 보낸 영역: platform(PC 1)
- 대상: pmart-clients `README.md`(문서만 — 코드 변경 없음), 빈 폴더 `docs/images/readme/`
- 근거: 사용자 2026-11-26 — 저장소는 쪼개지 않고 "보여주기"로(고객 앱·라이더 앱·MFC 앱·DC 앱(백오피스 웹 + 시뮬레이션)·백오피스 앱(웹 5개)), "pmart-clients 저장소 README 에는 링크랑 각 대표사진 1장 보이게 해줘. 이미지는 나중에 추가할거니까 위치만 지정해줘"
- 같은 묶음이 조직 대문 `code-and-pick/.github` `profile/README.md` "앱 구성" 절에 있다(PC 1).

## 할 일
1. `README.md` 를 아래 **전문**으로 바꾼다(문서 링크 절은 지금 것 그대로 들어 있음, 옛 "상태: 2026-10-24 코드 미작성" 줄은 뺐다).
2. 사진은 아직 없다 — 자리만. 사진 `<img>` 는 주석이라 지금은 화면에 안 보이고 깨진 이미지도 없다. 사용자가 나중에 `docs/images/readme/` 에 아래 이름으로 올리면 그 줄의 주석만 푼다.
3. `docs/images/readme/README.md` 에 파일 이름 표(아래 "사진 파일")를 둔다.
4. 화면 캡처에 실제 이름·전화·주소·이메일이 보이지 않게(공개 저장소) — 사용자에게 안내만.

## 사진 파일 (`docs/images/readme/`)
| 파일 | 앱 | 권장 |
|---|---|---|
| `customer-app.png` | 고객 앱 대표 화면(예: 홈) | PNG, 폰 세로 |
| `rider-app.png` | 라이더 앱 대표 화면(예: 배차·길 안내) | PNG, 폰 세로 |
| `mfc-app.png` | MFC 앱 대표 화면(예: 피킹) | PNG, 폰·PDA 세로 |
| `dc-app.png` | DC 앱 — CDC 백오피스 또는 CDC 시뮬레이터 | PNG, 가로 |
| `backoffice.png` | 백오피스 앱 — 통합 백오피스 대시보드 | PNG, 가로 |

## README.md 전문

````markdown
# PMart Clients

PMart 퀵 커머스의 클라이언트 앱 저장소입니다. Flutter 모바일 앱(고객·라이더·MFC 현장)과 React 백오피스 웹을 한 저장소에서 공통 코드와 함께 관리합니다.
백엔드는 [pmart-backend](https://github.com/code-and-pick/pmart-backend)(Spring Boot 마이크로서비스)이며, API 계약 정본은 [`contracts/openapi/`](https://github.com/code-and-pick/pmart-backend/tree/main/contracts/openapi)입니다. 프로젝트 전체 소개는 조직 페이지 [code-and-pick](https://github.com/code-and-pick)에 있습니다.

## 앱

### 📱 고객 앱
장보기·검색·장바구니·쿠폰·결제(토스페이먼츠)·주문/배송 조회·리뷰·AI 상담 — Flutter(Android)

- 코드: [`apps/customer_app`](apps/customer_app)

<!-- [대표 사진] docs/images/readme/customer-app.png 를 올린 뒤 아래 줄의 주석을 푼다 -->
<!-- <img src="docs/images/readme/customer-app.png" width="260" alt="고객 앱"> -->

### 🛵 라이더 앱
출근·배차 수락·픽업 인계(QR)·길 안내·전달 사진·정산 — Flutter(Android)

- 코드: [`apps/rider_app`](apps/rider_app)
- 시뮬레이터: [pmart-rider-simulation](https://github.com/code-and-pick/pmart-rider-simulation)

<!-- [대표 사진] docs/images/readme/rider-app.png -->
<!-- <img src="docs/images/readme/rider-app.png" width="260" alt="라이더 앱"> -->

### 📦 MFC 앱
MFC 현장 피킹·포장·인계, 입고·적치, 재고 실사 — Flutter(PDA)

- 코드: [`apps/mfc_app`](apps/mfc_app)
- 시뮬레이터: [pmart-mfc-simulation](https://github.com/code-and-pick/pmart-mfc-simulation)

<!-- [대표 사진] docs/images/readme/mfc-app.png -->
<!-- <img src="docs/images/readme/mfc-app.png" width="260" alt="MFC 앱"> -->

### 🏭 DC 앱
중앙 물류센터(CDC) 운영 — 입고·적치·보충·출고 작업·재고·품질 — React 백오피스 웹 + 현장 시뮬레이터

- 백오피스 웹: [`web/cdc-backoffice`](web/cdc-backoffice)
- 시뮬레이터: [pmart-cdc-simulation](https://github.com/code-and-pick/pmart-cdc-simulation)

<!-- [대표 사진] docs/images/readme/dc-app.png -->
<!-- <img src="docs/images/readme/dc-app.png" width="640" alt="DC 앱"> -->

### 🖥️ 백오피스 앱 (웹 5종)
본사·센터 운영자용 React 웹 — 한 번 로그인으로 오간다(SSO)

| 웹 | 코드 |
|---|---|
| 통합 백오피스 | [`web/backoffice`](web/backoffice) |
| MFC 백오피스 | [`web/mfc-backoffice`](web/mfc-backoffice) |
| CDC 백오피스 | [`web/cdc-backoffice`](web/cdc-backoffice) |
| 라이더 운영 백오피스 | [`web/rider-backoffice`](web/rider-backoffice) |
| 인사 백오피스 | [`web/hr-backoffice`](web/hr-backoffice) |

<!-- [대표 사진] docs/images/readme/backoffice.png -->
<!-- <img src="docs/images/readme/backoffice.png" width="640" alt="백오피스 앱"> -->

## 공통 코드
| 폴더 | 내용 |
|---|---|
| [`packages/pmart_core`](packages/pmart_core) | Flutter 공통 — HTTP·토큰·멱등 키·오류(Problem Details)·환경 설정 |
| [`packages/pmart_ui`](packages/pmart_ui) | Flutter 공통 UI |
| [`packages/pmart_api`](packages/pmart_api) | 백엔드 OpenAPI 계약에서 만든 API 코드(`contracts.lock` 에 계약 판 기록) |
| [`web/core`](web/core) · [`web/ui`](web/ui) | 웹 공통 — 인증·API·UI |

## 문서
- 작업 규칙: `CLAUDE.md`
- 아키텍처·스택·API 연동: `docs/architecture/client-architecture.md`
- 앱별 화면·규칙: `docs/apps/`
- 단계 계획: `docs/roadmap.md`
- 백엔드 요청 목록: `docs/backend-requests.md`

---

<div align="center">

**동성인재개발교육원** — https://www.dshrd.or.kr/

지도: 서성원 훈련 교사

</div>
````

## 확인해 주세요
- 앱 설명 한 줄(괄호 안 기능)이 지금 앱과 다르면 그쪽 기준으로 고친다. `packages/*` 설명도.
- 끝나면 회신(커밋 해시)만 주세요 — PC 1 이 조직 대문과 맞는지 본다.
