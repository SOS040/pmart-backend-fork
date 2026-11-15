# [pmart-backend → pmart-clients] 회신: 사건함·처리 대기 질문 3개 + 빌드 올림 (2026-11-16)

- 보낸 영역: platform
- 원 문서: pmart-clients `docs/handoff/backoffice/2026-11-16-backend-case-inbox-and-pending-work-done.md` §3, `web-common/2026-11-16-backend-session-roles-centers-done.md` §2(빌드 올리기)

## 1. 빌드 올림(LAN 확인)
| 앱 | 주소 | 진입 파일 |
|---|---|---|
| backoffice | https://192.168.0.40 | `index-Bw-245hF.js` |
| hr-backoffice | https://192.168.0.40:8444 | `index-Bkg8rNsZ.js` |
| mfc-backoffice | https://192.168.0.40:8445 | `index-ChSinewe.js` |

## 2. 질문 답
1. **사건 `summary` 가 없을 때** — 지금 여는 사건(도움요청·전달 실패·위치 끊김·오배송)은 모두 요약이 있다. 없으면 **종류 이름**(라이더 도움요청 등)을 보이면 된다(caseId 대신).
2. **오배송 `evidence.system`** — 직원이 사진을 올려 보관하는 서비스는 아직 없다. 라이더가 찍은 사진(도움요청·전달 실패의 `evidenceRefs`)을 가리킬 때는 `system: "delivery-execution"`, `ref` = 그 사진 키. 직원이 새로 올리는 사진 칸은 두지 말고, 근거는 요약(summary)에 적게 한다. 사건 쪽에는 `ref` 값만 `MisdeliveryConfirmed.evidenceRefs` 로 넘어간다.
3. **사가 `reasonCode` 한국어 이름** — 표는 없다(서비스마다 코드가 다르고 계속 는다). 코드 옆에 **`reasonMessage`(대부분 한국어 설명)** 를 보이면 된다 — 지금처럼.

## 3. 바꾼 것·주의
- **"결제 결과 다시 확인"은 사유 선택**으로 bff 를 맞췄다(9b25c526, LAN 배포) — 메모를 비우면 서버가 "결제 결과 다시 확인"으로 기록. "처리 완료"는 그대로 사유 필수(없으면 400).
- **명령 결과 조회 `GET /backoffice/commands/{ts}/{cmd}/{requestId}` 는 아직 501** — 지금 두 명령은 늘 `APPLIED` 로 답하므로 보통 부르지 않는다. 응답이 시간 초과·`UNKNOWN_RESULT` 면 목록을 다시 읽어 확인하게 해 달라(501 을 오류로 띄우지 말고).
- 아직 화면에 없는 것: 개인정보 열람 기록(`backoffice/2026-11-16-notice-pii-access-log-api.md`), 라이더 찾기·상세·원문 열람·직원 배송 찾기(`backoffice/2026-11-16-notice-rider-delivery-staff-apis.md` — 늦게 보냄, 지금 화면은 목).
