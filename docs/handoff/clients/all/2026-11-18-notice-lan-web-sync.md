# [pmart-backend → pmart-clients] 알림: LAN 웹 배포 — 병합 뒤 PC 1 이 맞춤, 올라간 버전은 version.txt (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: 웹 4개 — backoffice·hr-backoffice(PC 2)·mfc-backoffice(PC 3)·rider-backoffice(PC 5), web/core·web/ui
- 근거: 2026-11-17 오후 병합된 PR #64~#102 가 LAN 에 안 올라간 채 남았다(병합 뒤 PC 1 에 알리는 단계가 없었음). 사용자 결정 2026-11-18 "(가)·(다)".

## 바뀐 것
- PC 1 의 `deploy/lan/lan.sh web-sync` 가 pmart-clients main 과 LAN 웹 앱을 비교한다. `--apply` 면 뒤처진 앱만 빌드·배포한다.
  - (가) PC 1 PR 세션이 PR 확인 때마다 돌린다. (다) 30분마다 확인만 하고 뒤처지면 사용자에게 알린다.
  - 뒤처짐 = 올라간 버전 뒤에 `web/<앱>`·`web/core`·`web/ui`·`package.json`·`package-lock.json` 이 바뀜.
- **올라간 버전 보기**: `https://<PC 4>:<포트>/version.txt` — 첫 줄 = pmart-clients 커밋 SHA, 둘째 줄 = 올린 시각(UTC). 포트 443 backoffice·8444 hr·8445 mfc·8446 rider.
- 빌드는 PC 1 의 빌드 전용 폴더에서 main 그대로(계약 타입 `gen:api` 는 그때의 pmart-backend main 계약).
- 2026-11-18 첫 맞춤: 네 앱 모두 `1869c49`(#102) — version.txt 확인.

## 클라이언트 쪽에 부탁
- 병합 뒤 따로 알릴 필요는 없다. 급하면 PC 1 에 "웹 맞춰 줘"만.
- 빌드가 main 에서 깨지면(`tsc`·`vite build` 실패) 그 앱은 올라가지 않고 PC 1 이 알린다 — `build:lan` 이 main 에서 통과하게 유지해 달라.
