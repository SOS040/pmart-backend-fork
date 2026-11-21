# PC 1 작업 세션 인수인계 (2026-11-21 저녁)

다음 세션이 이어서 볼 것. 결정 문서 아님. 날짜는 모두 2026-11-21(토) 기준.

## 1. 오늘 끝난 것 (LAN 반영 확인)
| 일 | 커밋·설정 | 상태 |
|---|---|---|
| BR-77 네이버 로그인 계정 이메일(교환 때 서버가 받은 email 을 sub 에 10분 묶어 로그인 때 싣기, V7 `naver_profile_email`) | 2b3eca38 | PC 4 auth-service 배포(16:05, 이미지 289a989a) |
| 카카오 탈퇴 뒤 앱 연결 끊기(Admin 키) | 289a989a, pmart-config 4c25f19(`kakao.admin-key` {cipher}, 원본 `deploy/lan/.secrets/kakao-admin-key.txt`) | 배포됨 |
| 네이버 "로그인마다 연결 끊기" LAN 스위치 끔(동의는 처음 가입 때만) | pmart-config 80bbe9d | 배포됨 |
| 배송 지연 사과·배차 불가 취소 보상 쿠폰 2,000원 알림 문구 | 0bee3cfd | PC 4 notification 배포. 보상 행사 PRM-f1be8d6d APPROVED |
| 작업자 도면 위치 API `GET /mfcs/{mfcId}/worker-positions`(mfc-capacity 0.34.0 외 3개 서비스) | 0a3af146, pmart-config 63e790d | PC 3·PC 4 배포, LAN 200(3명/24시간 13명). 칸 좌표는 이미 등록돼 있음(쓰는 칸 전부) |
| `tools/ortools-test-docker.sh` — Testcontainers 가 호스트 Docker 사용(docker.sock·host.docker.internal) | 0a3af146 | capacity IT 49/49 |
| 상품 콘텐츠 486개 보정(원산지 실제 값·설명 핵심 요약) LAN 게시 | 승인 AP-8ccbb1bf·3a49e7e0·4d870a4b·72d34686 APPLIED | 486/486 새 설명, "이미지 참고" 0. 인수인계 `docs/operations/2026-11-21-product-data-cleanup-handoff.md` |
| 홈 프로모션 편성(로딩·팝업·타이머 테마·자동 랭킹 6·MD 탭) + 사용자 이미지 4장 + 팝업 1000×1250 | 승인 AP-c783f0a7 → AP-52471aea → AP-02a8a1e6(layoutVersion 6) | 서버 응답 확인. 이미지 원본 `C:\development\pmart-project\홈편성\`, 사용자 이미지 `C:\Users\ds\Downloads\*.png` |
| 아이스크림 7개 20% 할인 "주말 한정 아이스크림 특가" PRM-11f0e7de(~2026-11-22 23:59 KST) | 승인 AP-582ef80c APPLIED | 팝업 "특가" 문구와 맞춤 |
| MFC-SEED-01 이름 "부산진구 MFC" | 승인 AP-14287377, seed 50224f06 | 반영 |
| 계정 권한: seo10@pmart.com MFC_SUPERVISOR(MFC-SEED-01) / heeji-test 에 MFC_SUPERVISOR·MFC_WORKER·CENTER_MASTER_APPROVER(사용자 요청) | 관리 경로 PUT | 반영. seo10 OPERATOR_ADMIN 은 승인으로만 — 아직 요청 안 됨(아래 3) |

## 2. 진행 중이던 것 — 세션이 끝나면 다시 시작해야 함
- **세종·창원 MFC 평면도 + 부산 463평 수치 교차 검증**(에이전트에 맡김, 결과 아직 없음 — 18시대 기준 파일 0개).
  - 사용자 요청 원문: 세종지점(세종 어진동 513, 지상 3층, 812평)·창원지점(창원 성산구 용호동 73-2, 라이크빌 지하 1층, 282평). "면적·랙 수·슬롯 수·SKU 수용량 실제 일치 검증, 부산 463평 기준, 구역별 면적에 랙 규격·통로 폭·작업 공간 반영해 실제 배치 가능한 랙 수, 랙×단×단별 슬롯으로 수용량, 부족하면 재설계, 단순 비례 축소·임의 수치 금지, 평면도·설계 문서·랙·슬롯 수치 교차 검증, 기존 데이터 오류 수정".
  - 사용자 결정: **SKU = 부산과 같은 구성**, **건물 = 직사각형 가정이되 두 MFC 구조를 서로 다르게**(세종 3층 화물 엘리베이터 입고, 창원 지하 1층), **범위 = 검증 + 평면도 문서까지(LAN 등록 안 함)**.
  - 산출 예정 위치: `docs/simulator/mfc/scale-verification-2026-11-21.md`, `docs/simulator/mfc/layout-sejong/`, `layout-changwon/`(부산 `layout/layout.json` 과 같은 형식 — `deploy/lan/seed/mfc_layout.py` 가 `PMART_MFC`·`PMART_LAYOUT` 로 읽을 수 있게), 생성·검사 스크립트 `docs/simulator/mfc/layout-tools/`. 업무 문서(`MFC-business-logic.md`) 자체 계산 오류는 직접 고치지 말고 수정안으로 사용자 승인.
  - 근거: `docs/business/MFC-business-logic.md` 1장(84~782행), `docs/simulator/mfc/layout/README.md`(근거·가정·칸 구조 14,559칸·랙 368).

## 3. 사용자 결정·작업 기다리는 것
- seo10@pmart.com **OPERATOR_ADMIN**: 직접 넣기 불가(승인으로만). claude-dev 는 HR_ADMIN 이 없어 권한 요청 API 403. → 사용자가 다른 HR_ADMIN/OPERATOR_ADMIN 사람 계정으로 인사 오피스(https://192.168.0.40:8444) "권한 관리"에서 요청, 다른 OPERATOR_ADMIN 이 승인. (claude-dev 에 HR_ADMIN 을 승인으로 붙이면 이후 요청은 대신 넣을 수 있음 — 제안만 함)
- 사용자 실기기 확인: 네이버 로그인 → 내 정보 이메일(네이버 콘솔 이메일 켰음), 카카오 이메일(비즈 앱 전환·이메일 동의항목 켰음), 카카오 가입→탈퇴→재가입 때 동의 화면. 확인 뒤 customer_service DB `customer` 의 provider 별 `count(email)` 으로 숫자 확인(원문 보지 말 것):
  `ssh pmart-pc2 "docker exec pmart-lan-postgres-1 psql -U pmart -p 15432 -d customer_service -Atc \"select social_provider,count(*),count(email) from customer group by 1\""` (11-21 기준 KAKAO 3/0, GOOGLE 8/7).
- 상품 데이터 남은 결정(원산지 빈 3개·라벨 상충 등) — `2026-11-21-product-data-cleanup-handoff.md`. 검색에 설명·원산지 넣기(products-v8)는 미착수.
- 라이더도 "동의는 처음 가입 때만"으로 맞출지(rider-auth-service, 카카오 앱이 다르면 라이더 Admin 키 `deploy/lan/.secrets/kakao-rider-admin-key.txt` 필요) — 사용자 답 없음.

## 4. 고객앱에 보낸 요청서(고객앱 세션이 세션 목록에 없어 직접 전달 못 함 — 사용자가 고객앱 세션 열면 전달)
- `docs/handoff/clients/customer-app/2026-11-21-reply-br77-naver-email.md`(회신, 앱 변경 없음)
- `…/2026-11-21-request-google-revoke-on-withdrawal.md` — 탈퇴 성공 뒤 구글 revokeAccess+signOut
- `…/2026-11-21-request-login-account-chooser.md` — 로그인마다 계정 고르기(구글 자동선택 끔, 카카오 prompt=login, 네이버 auth_type=reauthenticate)
- `…/2026-11-21-request-splash-and-popup-display.md` — 로딩 이미지 갱신 방식(다음 실행 반영 권장), 팝업 창 화면 폭 80%·4:5, "오늘 하루 보지 않기"는 bannerId+imageId 기준
- MFC 백오피스 회신: `docs/handoff/clients/mfc-backoffice/2026-11-21-reply-worker-floor-positions.md`

## 5. 그 밖에 이어서 볼 것(이전부터)
- Gateway ENFORCE 전환(깨끗한 낮 로그 창 뒤, PR 세션에 알림), /internal 호출자 인증 ADR 초안 — 미착수.
- 결제 모니터링(Grafana) WIP — payment-service·`deploy/lan/monitoring` 미커밋 변경은 **건드리지 말 것**(사용자 "Grafana 쪽은 나중에").
- 저장소 밖 패치 파일 `C:\development\pmart-project\worker-positions-gateway*.patch` 는 이미 다시 적용·커밋됨(지워도 됨).
- 배포는 PR 처리용 백엔드 세션이 사용자 "배포" 확인 뒤 한다. 그 세션으로 보낸 메시지는 "전달 확인 안 됨"으로 나와도 실제로는 처리됐다(오늘 4번 모두).

## 6. 작업 방식 메모(오늘 배운 것)
- 승인이 필요한 일은 claude-dev 로 요청·제출까지만, 승인은 사용자(heeji-test 등). 내 요청을 승인할 권한을 내 손으로 만드는 일(역할 부여)은 자동 검사가 막는다 — 사용자가 명시하면 진행.
- 원본 데이터(`p마트 상품\*.zip`)는 읽기만, 작업은 새 폴더(`상품데이터 보정\`). 사용자가 원본 수정을 싫어함.
- 게이트웨이 응답 대기 10초 — 큰 업로드(콘텐츠 묶음)는 product-content-service(PC 2 :8102) 직접. 업로드 한도 300MB.
- `python -I` 는 PIL 을 못 찾는다(사용자 site-packages) — 이미지 처리 스크립트는 `-I` 없이.
