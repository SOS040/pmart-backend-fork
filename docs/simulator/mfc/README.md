# MFC 시뮬레이터 (백엔드 쪽 문서)

- 시뮬레이터 저장소: `../pmart-mfc-simulation`(백엔드 작업에서는 읽기만, 요청이 있을 때만 그 저장소 main 에 문서). 시뮬레이터 → 백엔드 요청은 그 저장소 `docs/backend-requests.md`.
- 근거 업무 문서: `docs/business/MFC-business-logic.md`(WMS 문서는 CDC 용 — MFC 시뮬레이터에는 쓰지 않았다, 2026-11-09 검토).

| 문서 | 내용 |
|---|---|
| [mfc-simulator-integration.md](mfc-simulator-integration.md) | 실서버·실제 앱 연결 목표와 결정(SIM-1~11) |
| [layout/](layout/README.md) | 평면 도면(시뮬레이터 `buildLayout()` 실행 좌표), 백엔드 칸 대응표, 처음 구성 때 근거, MFC 문서와 다른 점, 다시 그리는 도구 |
| [scale-verification-2026-11-21.md](scale-verification-2026-11-21.md) | 부산 진구점 463평 면적·랙·칸·SKU 교차 검증(기하 배치), 문서·평면 불일치 목록 |
| [layout-sejong/](layout-sejong/README.md) | 세종지점 설계안(3층 812평, 직선 흐름·긴 빗살 통로) — 등록 안 함 |
| [layout-changwon/](layout-changwon/README.md) | 창원지점 설계안(B1 282평, U 흐름·촘촘한 블록) — 등록 안 함 |
| [layout-tools/](layout-tools/build_layout.py) | 설계안 생성(`build_layout.py`)·세 센터 검사(`check_layouts.py`)·부산 검증 계산(`verify_busan.py`) |
