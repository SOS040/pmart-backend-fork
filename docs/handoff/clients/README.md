# handoff — 저장소 간 요청·회신

pmart-backend 와 pmart-clients 는 서로의 저장소에 쓰지 않는다. 각자 자기 `docs/handoff/` 에만 쓰고 상대 것은 읽기만 한다.

| 방향 | 위치 |
|---|---|
| 클라이언트 → 백엔드 (요청·재질의) | `pmart-clients/docs/handoff/` |
| 백엔드 → 클라이언트 (회신·공지) | `pmart-backend/docs/handoff/clients/` (이 폴더, 2026-11-09 전에는 `docs/handoff/` 바로 아래) |

- 파일 이름은 `YYYY-MM-DD-<받는 앱 영역>-<종류>-<주제>.md`(2026-11-14 부터 — 받는 앱 영역 customer-app·mfc-app·rider-app·backoffice·hr-backoffice·web-common·all, 종류 request·reply·notice). 첫머리에 `보낸 영역: platform|mfc|delivery` 와(회신이면) 원 요청서 경로를 적는다. 2026-11-13 까지는 `YYYY-MM-DD-clients-…`. 영역 규칙 `docs/operations/pc-split.md`.
- 항목 번호(BR-xx)는 양쪽이 그대로 쓴다. 항목마다 수용 / 대안 / 보류와 계약 반영 커밋 SHA를 적는다.
- 계약 커밋을 push한 뒤 회신 파일을 올린다. 상대는 pull 후 읽으므로 계약과 회신이 어긋나지 않는다.
