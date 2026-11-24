# 회신 — 1호점 부산진구 센터 ID `MFC-SEED-01` → `MFC-BJG-01` (2026-11-24, R-46)

보낸 영역: platform (PC 1). 요청: 시뮬레이터 R-46(사용자 "부산진구 실서버 센터 ID 바꿔" → "보내"), PC 1 사용자 "DB 까지 전부 바꿈" → "진행".
상태: **LAN 반영 끝·시뮬레이터 대조 어긋남 0**(mfc 시뮬4 — 지점 id·기본 센터 MFC-BJG-01, MOCK 별칭 없앰, 새 토큰으로 16,853칸·위치 차이 0·재고 1,334줄 9,671개). 옛 ID 별칭은 두지 않는다(옛 ID 로 부르면 404·403·빈 결과).

## 1. 바꾼 것
| 곳 | 내용 |
|---|---|
| 서비스 DB | 25 DB 86,896줄(문자열·JSON·배열 열의 `MFC-SEED-01` 을 `MFC-BJG-01` 로 — DB 마다 한 트랜잭션, 외래 키 검사 끔). 위치 16,853·재고 원장 30,330·center-master·직원 소속(employee-auth·hr·auth)·배송·라이더 배달지역·장바구니·주문·클레임 등 |
| 설정 저장소 | pmart-config ef38be5 — fulfillment·orchestration 센터별 키 6줄(lan·local) |
| 검색 색인 | `sku-handlings`·`products` 각 503건(`centers` 키·`carriedMfcIds`) — `_update_by_query` |
| LAN 스크립트 | `deploy/lan/seed`·`checks` 기본값, `seed-data.json`, 코드 주석 2곳 |

**그대로 둔 것(이력)**: 멱등 응답 기록(`request_record.result`), 처리한 사건(`inbox_event.aggregate_id`), 상태 사유 글, 계정 변경 이력, 감사 기록, Kafka 의 지난 사건, outbox, 그리고 재고 LOT ID(`LOT-MFC-SEED-01-…` 꼴 — 다른 서비스와 같이 쓰는 ID 라 바꾸지 않음). 원본 고정본 `seed-template-2026-11-24.json` 도 그대로.

## 2. 실행
1. 시뮬레이터 개발 서버 끔(이쪽 확인) → 감시 작업 끔 → Kafka 지연 0 확인 → 업무 컨테이너 41개 멈춤(pc2 16·pc3 7·pc4 8·pc5 10, DB·Kafka·ES·Keycloak 은 켬)
2. 모든 DB 백업 `pg_dump -Fc` — 각 PC `%USERPROFILE%\pmart-backups\r46-20261124\<db>.dump`
3. 되돌리기 시험(ROLLBACK)으로 숫자 확인 → 실제 바꾸기 — 시험과 같은 86,896줄, 오류 0
4. 설정 push → 컨테이너 시작 → 검색 색인 고침 → 감시 작업 켬

## 3. 확인
- `GET /locations?mfcId=MFC-BJG-01` 16,853칸(ACTIVE 9,086·CLOSED 7,767), 재고 1,334줄 9,671개. `MFC-SEED-01` 은 재고 0, 센터 관리자 토큰으로 403, center-master 404.
- 고객 검색 `mfcId=MFC-BJG-01` 결과 있음(AVAILABLE), `MFC-SEED-01` 0건. Redis 옛 ID 키 0.
- DB 다시 스캔: 옛 ID 는 위 "그대로 둔 것" 열에만.
- 서비스 ERROR: 켤 때 게이트웨이 연결 끊김(라이더 `/offers` 등) 몇 건 뒤 0.
- 검색 색인 고친 스크립트(재색인 뒤 다시 쓸 것): painless — `carriedMfcIds` 의 옛 ID → 새 ID(겹치면 하나, 정렬), `centers` 의 옛 키 → 새 키. 쿼리 `"MFC-SEED-01"`.

## 4. 알아 둘 것
- 직원 토큰: 이미 받은 토큰에는 옛 센터가 들어 있다 — 1호점 직원·시험 계정은 다시 로그인.
- 클라이언트: 센터 ID 를 코드에 박은 곳은 시험·주석뿐(세션 centers·주소 `/c/:mfcId`) — 옛 주소 북마크만 영향.
- 되돌리기: 백업 dump 를 `pg_restore --clean` 으로(서비스 멈춘 뒤), 설정 저장소 되돌리기, 색인은 같은 스크립트를 반대로.
