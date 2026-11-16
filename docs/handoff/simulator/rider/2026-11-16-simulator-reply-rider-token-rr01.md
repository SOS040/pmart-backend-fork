# [pmart-backend → pmart-rider-simulation] 회신: RR-01 시뮬레이터 라이더 토큰 (2026-11-16)

- 보낸 영역: platform(PC 1)
- 원 문서: `pmart-rider-simulation/docs/backend-requests.md` RR-01(백엔드 `main` `aa46ce11` 기준 작성)
- 근거: 시험 라이더 토큰 결정 `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(2026-11-10 — LAN 서명키로 직접, 15분, LAN 전용, rider-auth 에 임시 로그인 코드 없음), 전례 `deploy/lan/checks/rider_e2e.py`

## 결정 — 사용자 2026-11-16 "A번" : 시뮬레이터 개발 서버가 LAN 서명키로 직접 만든다
rider-auth-service 에 시험 토큰 발급 경로는 만들지 않는다(로그인 코드는 늘리지 않는다 — 11-10 결정 그대로). 아래 조건을 지킨다.

1. **어디서**: Vite 개발 서버(Node)에서만 서명한다. 브라우저·번들·localStorage·sessionStorage 에 서명키를 두지 않고, 브라우저에는 토큰만 준다.
2. **키**: `PMART_SECRETS_DIR`(기본 `../pmart-backend/deploy/lan/.secrets`)의 `rider-auth-signing-key.pem`·`rider-auth-signing-kid` 를 **읽기만**. 키가 있는 PC 는 **PC 1·PC 5 두 대뿐**(아래 갱신) — 그 밖의 PC·공유 폴더·Git·메신저로 옮기지 않는다. 운영(prod) 키에는 쓰지 않는다 — LAN 전용.
3. **누구의 토큰**: 주체(`sub`)는 **시뮬레이터 전용 라이더 ID 만**(`RD-sim-` 접두어). 서명 함수 안에서 접두어가 아니면 거절한다 — 이 키는 어떤 라이더 토큰이든 만들 수 있으니 실제 라이더를 대신하지 않게 코드로 막는다.
4. **모양**: `rider_e2e.py` 와 같게 — RS256, `kid` = 서명키 ID, `iss` = LAN rider-auth 발급자, `aud: ["pmart-api"]`, `typ: "RIDER"`, `roles`·`centers` 빈 배열, `sid` 는 `sim-` 로 시작하는 새 값, **유효 15분**(`exp = iat + 900`), `jti` 새 값. 만료되면 새로 만든다(재사용·연장 없음).
5. **LAN 다른 기기 조회(용도 2)**: 다른 기기에는 토큰을 주지 않는다. 개발 서버가 **GET·HEAD 허용 목록**(라이더 본인 조회 — `/riders/me/state`·`/riders/me/locations/latest`·`/riders/me/deliveries`·`/offers` 등)만, **시뮬레이터 라이더 ID 에 한해** 토큰을 붙여 넘긴다. 쓰기·전달지(`/deliveries/{id}/drop-info` — 고객 개인정보)는 넘기지 않는다. 직원용 라이더 위치 조회 API 가 생기면 그것으로 바꾼다.
6. **기록**: 토큰·서명키·고객 주소·전화번호를 로그·일지·화면에 남기지 않는다. 요청 로그에는 라이더 ID·경로·상태 코드만.
7. **세션 차단**: 라이더 정지 등으로 세션이 끊기면(블록리스트, ADR-0054 ⑤) 그 토큰은 게이트웨이에서 401 이 된다 — 401 이면 새 토큰을 만들되, 같은 라이더가 정지 상태면 운행하지 않는다.

## 아직 정하지 않은 것 (사용자 결정 대기)
- RR-02(ID 접두어·인원·전화번호 대역), RR-03(관찰 계정으로 포장 QR 읽기), RR-04(카카오모빌리티 길찾기 직접 호출 — 키·비용) 는 따로 회신한다. RR-02 회신 전까지 접두어는 요청서대로 `RD-sim-`.

## 갱신 (2026-11-16, 같은 날) — 작업 PC 는 PC 5
- 라이더 시뮬레이터 작업은 **PC 5(delivery 영역)** 에서 한다. 사용자가 PC 5 에 LAN rider-auth 서명키를 직접 나눠 줬다("어쩔 수 없이" — 사용자 결정). PC 5 는 `rider_e2e.py` 때부터 같은 키를 갖고 있었다.
- 그래서 위 2 의 "키 복사 금지"는 **"PC 1·PC 5 밖으로 옮기지 않는다"** 로 읽는다. PC 5 에서도 키는 `deploy/lan/.secrets/`(Git 제외) 한 곳에만 두고, 시뮬레이터 `.env.local`·저장소 안에 복사하지 않는다(`PMART_SECRETS_DIR` 로 가리킨다).
- 이 키로는 어떤 라이더 토큰이든 만들 수 있다 — 조건 3(`RD-sim-` 만 서명)을 코드로 꼭 지킨다.
- 나중 일(PC 1): 라이더 앱 실제 로그인으로 시험이 돌게 되거나 키가 더 퍼지면 LAN rider-auth 서명키를 바꾼다(`init-secrets.sh` 로 새 키 → pmart-config `{cipher}` → rider-auth 재배포, 새 키를 PC 5 에 다시 전달).

