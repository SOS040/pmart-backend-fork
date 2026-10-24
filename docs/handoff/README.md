# handoff — 저장소 간 요청·회신

pmart-backend 와 pmart-clients 는 서로의 저장소에 쓰지 않는다. 각자 자기 `docs/handoff/` 에만 쓰고 상대 것은 읽기만 한다.

| 방향 | 위치 |
|---|---|
| 클라이언트 → 백엔드 (요청·재질의) | `pmart-clients/docs/handoff/` |
| 백엔드 → 클라이언트 (회신·공지) | `pmart-backend/docs/handoff/` (이 폴더) |

- 파일 이름은 `YYYY-MM-DD-<상대>-<주제>.md`. 회신 파일 첫머리에 원 요청서 경로를 적는다.
- 항목 번호(BR-xx)는 양쪽이 그대로 쓴다. 항목마다 수용 / 대안 / 보류와 계약 반영 커밋 SHA를 적는다.
- 계약 커밋을 push한 뒤 회신 파일을 올린다. 상대는 pull 후 읽으므로 계약과 회신이 어긋나지 않는다.
