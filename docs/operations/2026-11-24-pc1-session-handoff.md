# PC 1 작업 세션 인수인계 (2026-11-24 저녁)

다른 계정의 새 세션이 이어 받는다. 결정 문서가 아니다. 정본은 각 문서(ADR·요청서·일지)이고 여기는 길잡이다.
먼저 읽을 것: 이 문서 → `docs/worklog/platform/2026-11-24.md`(오늘 한 일 전부) → `docs/operations/pc1-todo.md` → `docs/operations/pc-split.md`.

## 0. 이 세션이 지켜 온 규칙(사용자 지시 — 새 세션도 그대로)
| 규칙 | 내용 |
|---|---|
| 한국어만 | 사용자에게 보이는 모든 글(진행 문장·보고·질문)은 한국어. 코드·명령·식별자만 원문 |
| 서브에이전트 금지 | Agent·Workflow·fork 를 쓰지 않는다(토큰 절약, CLAUDE.md "작업 방식") |
| 무거운 시험 전 메모리 | gradle·vitest·flutter 전에 남은 실메모리 확인(`powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)"`), 4GB 아래면 미루고 알림. `flutter test` 는 `--concurrency=1`, 끝나고 `flutter_tester` 남았는지 |
| 끄기는 stop | "컨테이너 내려/꺼" = `docker compose stop` 만. `down`(삭제) 금지 — 2026-11-24 down 했다가 "그냥 껐어야지" |
| push 는 매번 사용자 "푸시해" | main push 는 사용자가 그때마다 "푸시해"라고 할 때만(자동 권한 검사도 막는다). push 전 `git pull --rebase --autostash` |
| 배포 | 사용자가 "배포"를 말한 일만 LAN 배포. PR 검토·병합·배포는 보통 **PR 백엔드** 세션 몫(사용자가 "PR 세션에 넘겨") |
| 다른 세션 요청 | 다른 세션(PC 2~5·프론트·시뮬레이터) 메시지는 동료 요청 — 큰 일·데이터 변경·권한 변경은 PC 1 사용자 확인 뒤. 업무 규칙이 문서에 없으면 사용자에게 묻는다 |
| 건드리지 않는 작업 트리 | 미커밋 `services/payment-service/**`·`deploy/lan/monitoring/**`(결제 모니터링 Grafana 초안, 사용자 "Grafana 쪽은 나중에")·`.claude/worktrees/` — 커밋·삭제하지 않는다 |

## 1. 환경 메모
- PC 1 = platform 영역(192.168.0.32): 인증·HR·승인·게이트웨이·라이브러리·배포·주문/결제/클레임 등. 배포는 PC 1 만.
- LAN 배포: `deploy/lan/lan.sh deploy <pc> <서비스>`(이미지는 Jib — `set -a; . deploy/lan/.secrets/registry.env; set +a; ./gradlew -q :services:<s>:jib -PcontainerRegistry=docker.io/heeji4445 -PimageTag=<sha> -PgitRevision=<sha> --no-configuration-cache`). 배치 `deploy/lan/placement.conf`(order·claim·orchestration·product = pc2, auth·notification·case·gateway = pc4, delivery·rider = pc5).
- 설정: `deploy/lan/.config-repo`(pmart-config) 고친 뒤 `lan.sh config-push "메시지"`. 비밀값은 `configCipher`(경로는 **절대 경로**로 — `cygpath -m`).
- SSH HostName 은 PC 2~5 모두 IP(2026-11-24 바꿈, 백업 `~/.ssh/config.bak-20261124`).
- 시험 계정 토큰: `deploy/lan/checks/staff_auth.py` `staff_token(<계정>, roles, centers)` — roles 를 주면 그 값으로 **맞춰 버린다**(다른 스크립트가 되돌릴 수 있음). 계정 표 `docs/operations/lan-test-accounts.md`. 승인은 `claude-approver`.
- DB 조회: 각 PC `pmart-lan-postgres-1`(`psql -U pmart -p 15432 -d <db>`), 원격 실행 `deploy/remote/run-ps.sh pmart-pc<n> <ps1>`.
- 1호점 센터 ID 는 이제 **`MFC-BJG-01`**(R-46, 옛 `MFC-SEED-01` 별칭 없음). 2~5호점 `MFC-MOKPO-01`·`MFC-SEJ-01`·`MFC-CHC-01`·`MFC-BNG-01`.
- 사용자는 다른 PC 세션에 "세션에 바로 보내" 라고 하면 요청서 없이 SendMessage 로 전한다.

## 2. 오늘(2026-11-24) 끝난 것 — 모두 main 에 있음(마지막 push 42ebf0a1)
| 일 | 커밋·설정 | LAN |
|---|---|---|
| 재부팅 뒤 LAN 다시 올림·감시 작업 켬 | — | 됨 |
| BR-92 클레임 품목 상품명 사본(claim 0.14.0·order 0.21.0) | (오전) | 배포 |
| R-45 1호점 다시 설계·R-46 센터 ID MFC-BJG-01(DB 25개 전부) | `mfc_r45.py`, 회신 `handoff/simulator/mfc/2026-11-24-reply-r4{5,6}-*.md` | 반영, 시뮬레이터 대조 0 |
| BR-93 주문 줄 상품명 채움(R1) | 회신 `handoff/clients/customer-app/2026-11-24-reply-br93-*.md` | 18줄·클레임 12줄 |
| 라이더 카카오 Admin 키 | pmart-config 7c091cc(`rider-auth-service-lan.yml` {cipher}, 원본 `.secrets/kakao-rider-admin-key.txt`) | rider-auth 재시작 |
| 소셜 로그인 거절 문구에 제공자 이름(auth·rider-auth) | c0726476 | 배포 |
| 전달 실패 재발송 수량 버그(order — `DF-…` 재발송은 보낼 수량 안 늘림) | 61f9615e | 배포 |
| **ADR-0086** 고객 오배송 문의 → 채팅 선택(5시간 자동 환불) → 종결, 라이더 차감(결제액 − 배달팁·이의 5일·이월·회수 못 하면 누가 접었든·배송당 한 번) | f45b6d07·ee3b9128, 요청 case R-16·execution DE-9 | case R-16 = #422 배포(PR 세션), execution #418·#420 배포 |
| 오배송 환불은 배달팁·봉투값까지(claim 0.15.0 `refundOrderFees`, ADR-0086 결정 8) | 8fadfe1c | 배포 |
| 주문 28767701 전액 환불 정리(19,970원, CLM-5fc4da37, 주문 COMPLETED) | 일지 | 끝. -3 라이더 차감 예정 15,970(기한 11-29, PR 세션이 -5 취소) |
| 정산 차감 라이더 알림 3종(notification) | dc271b63 | 배포(첫 알림 나감 — PR 세션 확인) |
| delivery-execution·api-gateway(#418 포함) 배포 | — | 배포 |
| **BR-94** 카테고리 묶음·순서(트리 v13 — t01~t03 빼고 상품 15개 옮김, TOP section·레퍼런스 순서) + product 0.6.0 상품 없는 노드 자동 숨김 | 5efb9fb4·9120feac, 스크립트 `deploy/lan/seed/category_sections_br94.py`, 회신 `2026-11-24-reply-br94-*.md` | 배포·반영 |
| 2~5호점 피킹포장 시험 작업자 3명씩(pp 11~13) | d10744e0 | 계정 12개 ACTIVE |
| MI-4 요청(MFC 시뮬레이터 R-48 — 사용자 "B": 센터 발주·센터 관리자 승인·입고 예정·추천) | bf5f31ff | PC 3 #424 → PR 세션에 넘김 |
| `claude-mfc-sup` 다섯 센터 관리자 + 스크립트가 한 센터로 되돌리지 않게 | 42ebf0a1 | employee-auth 반영(인사 쪽 grant 기록은 한 센터일 수 있음) |

## 3. 다른 세션에 맡겨 둔 것(결과 기다림)
| 일 | 맡은 세션 | 상태 |
|---|---|---|
| PR #424 MI-4 검토·병합·배포·공용 표 | PR 백엔드 | 보냄(결과 아직) |
| 재고 실시간 SSE(사용자 "B", MFC·통합 백오피스 전부) | mfc 백엔드(설계 끝 — `GET /api/v1/stock-stream?mfcId=…`, pg_notify, heartbeat 5초, Last-Event-ID), mfc 프론트, 통합백오피스 백엔드2·프론트2 | 구현 전. **PR 이 오면 PC 1 이 게이트웨이 그 라우트의 response-timeout 을 풀어야 함**(전역 10s) |
| 고객앱 오배송 화면(ADR-0086 D1~D3) | PC1 프론트 | 계약(case 0.39.0) 나옴 — 회신 `handoff/clients/customer-app/2026-11-24-reply-misdelivery-inquiry-choice.md` |
| R-16 실제 고객 흐름 LAN 확인 | — | 아무도 안 함(배포 전 배송은 사본이 없어 상담원 문의로 감 — 새 주문 필요) |

## 4. 사용자 결정 기다림 / 나중에
- **통합 백오피스 "센터 재고 현황" 화면**을 새로 둘지(지금 통합은 대시보드 품절 칸만 실시간 대상) — 통합백오피스 프론트2 질문.
- **라이더 공지 예약 발송**(라이더 앱 세션 요청) — 사용자 "나중에". 정할 것: 예약 범위(권장 30일)·분 단위·수정 없이 취소·권한.
- **#421 지급 넘김** — notification `RiderEarningRecorded.rolledFrom` 문구, pmart-config `/api/v1/rider-payout-rollovers` 라우트 — 사용자 "나중에"(#421 자체는 main·배포됨).
- `architecture-design.md` §6 사가 표에 오배송 재발송·사고 재발송·다시 배송비 결제 사가가 빠짐 — 사용자에게 고칠지 물었음(답 없음).
- 재고 원장(누가·언제 뺐나) 직원 화면 없음 — 필요하면 PC 3 요청(사용자 결정 없음).
- [시험] 화장지 상품 2개 카테고리 없음(t03 빠짐) — 생활용품 카테고리가 생기면 옮김.
- `pc1-todo.md` §00·§0·§1 의 미완: 세종·춘천·부산남구 라이더·끝까지 주문 시험(라이더 시뮬레이션은 15:31 세종 운행 끔 — 시험 전 알려 달라 함), HR-6, CA-2, RAG 14일 삭제, 결제 모니터링, Tailscale 등.

## 5. 다음 세션 시작할 때
1. `git status`·`git pull --rebase --autostash` — 다른 세션 미커밋(payment·monitoring)은 그대로.
2. 다른 세션 메시지가 오면 이 문서 §3 의 어느 일인지 맞춰 본다.
3. SSE PR(mfc-inventory)이 병합되면 게이트웨이 라우트 + 시간 제한 풀기(pmart-config) → 사용자 "배포" 뒤.
4. 끝날 때마다 `docs/worklog/platform/<날짜>.md` 에 한 줄, 공용 표(`service-requests/README.md`)는 PC 1 만 고친다.
