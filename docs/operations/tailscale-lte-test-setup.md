# Tailscale — 휴대폰 데이터(LTE)로 LAN 게이트웨이 쓰기 설정

- 작성: PC 1, 2026-11-19 (처음 설정 2026-11-18 — `docs/worklog/platform/2026-11-18.md`)
- 목적: 고객 앱·라이더 앱을 **와이파이가 아니라 휴대폰 데이터**로 LAN 게이트웨이(PC 4 `192.168.0.40`)에 붙여 시험한다(라이더 LTE 위치 시험 등).
- 구조: 휴대폰(Tailscale) → **PC 1(서브넷 라우터, `192.168.0.40/32` 하나만 광고)** → LAN → PC 4 게이트웨이. PC 1 이 꺼져 있으면 안 된다.
- **임시 시험 경로**다 — 시험이 끝나면 아래 "내리기"대로 닫는다(`docs/operations/pc1-todo.md` §1).
- 계정 이메일·비밀번호는 이 문서와 저장소에 적지 않는다.

## 1. PC 1 (Windows) — 계정 바꾸기·경로 열기
1. 이전 계정에서 로그아웃 — 작업 표시줄 Tailscale 아이콘 → 계정 → **Log out**, 또는 관리자 PowerShell:
   ```powershell
   & "C:\Program Files\Tailscale\tailscale.exe" logout
   ```
2. 새 계정으로 로그인 — 아이콘 → **Log in** → 브라우저에서 새 계정(사용자가 직접).
3. 게이트웨이 경로 광고(관리자 PowerShell):
   ```powershell
   & "C:\Program Files\Tailscale\tailscale.exe" set --advertise-routes=192.168.0.40/32
   ```
   - 처음부터 다시 잡을 때는 `tailscale.exe up --advertise-routes=192.168.0.40/32 --reset`(2026-11-18 에 쓴 명령).
   - 확인: `tailscale.exe debug prefs` 의 `AdvertiseRoutes` 에 `192.168.0.40/32`.
4. (안 닿을 때만) PC 1 의 Windows IP 전달은 꺼져 있다(2026-11-18 확인) — 휴대폰에서 안 닿으면 켜야 할 수 있다. 바꾸기 전에 PC 1 작업 세션과 상의.

## 2. 관리 화면 (https://login.tailscale.com/admin) — 새 계정으로
### 2-1. 경로 승인
- **Machines** → PC 1(`ds603-13`) 줄 `…` → **Edit route settings** → `192.168.0.40/32` 체크 → Save.
- 이걸 빠뜨리면 휴대폰에서 안 닿는다(아래 2-3 의 autoApprovers 를 넣으면 자동 승인).

### 2-2. 키 만료 끄기
- **Machines** → PC 1 줄 `…` → **Disable key expiry** → 줄에 "Expiry disabled".
- 켜 두면 몇 달 뒤 PC 1 인증이 만료돼 연결이 갑자기 끊긴다. 휴대폰은 만료돼도 앱에서 다시 로그인하면 되므로 그대로 둬도 된다.

### 2-3. 정책(Access controls) — 게이트웨이 두 포트(HTTP 8080·HTTPS 8443)만
**Access controls** → 정책 파일을 아래로 바꾸고 Save:
```json
{
  // LAN 게이트웨이(PC 4) 두 포트만 — 휴대폰 LTE 시험용. 앱 LAN 실행 설정은 HTTP 8080, 웹·일부 빌드는 HTTPS 8443
  "grants": [
    {
      "src": ["autogroup:member"],
      "dst": ["192.168.0.40/32"],
      "ip":  ["tcp:8080", "tcp:8443"]
    }
  ],
  // (선택) PC 1 이 이 경로를 광고하면 관리 화면 승인 없이 바로 쓰게
  "autoApprovers": {
    "routes": {
      "192.168.0.40/32": ["<새 계정 이메일 — 직접 넣기>"]
    }
  }
}
```
- **고객 앱 LAN 실행 설정(Android Studio `customer-lan`)은 `http://192.168.0.40:8080`** — 8443 만 열면 "서버에 연결할 수 없다"가 난다(2026-11-19 처음 안내가 틀려 실제로 남). HTTPS 빌드(`https://192.168.0.40:8443`, `lan_trust.dart`)도 있어 두 포트를 다 연다.
- 앱별 포트(2026-11-19 확인):
  | 앱 | 8080(HTTP) | 8443(HTTPS) |
  |---|---|---|
  | 고객 앱(`customer-lan`) | API 전부 | — |
  | MFC 앱(`mfc-lan`) | API 전부(`/api/v1`, 로그인 시작 `/employee-auth/logins`) | **직원 로그인 화면**(브라우저 — Keycloak `https://192.168.0.40:8443/iam`, 돌아오는 주소 `…:8443/api/v1/employee-auth/logins/callback`) |
  → 8443 을 빼면 MFC 앱은 로그인 화면이 안 열리고, 8080 을 빼면 둘 다 API 가 안 된다.
- Tailscale 이 켜진 휴대폰은 **와이파이에 있어도** `192.168.0.40` 요청이 PC 1 을 거친다 — 정책·PC 1 이 막으면 와이파이에서도 안 된다. 급하면 휴대폰 Tailscale 을 끄면 와이파이로 바로 간다.
- 이 정책을 저장하면 기본 "전부 허용"이 없어진다 — 휴대폰에서 PC 1 의 다른 포트·다른 기기는 못 간다(시험 용도로 맞음).
- `autoApprovers` 가 필요 없으면 그 블록을 지운다.

### 2-4. 2단계 인증
- 새 계정을 만든 로그인 제공자(Google·GitHub 등) 쪽에서 켠다(Tailscale 자체 설정이 아님).

### 2-5. 이전 계정 정리(선택)
- 이전 계정 관리 화면 → Machines → 남은 PC 1·휴대폰을 **Remove**.

## 3. 휴대폰 (Android)
1. Tailscale 앱 → 오른쪽 위 계정 → **Log out**(이전 계정이었으면).
2. 새 계정으로 로그인 → 연결 켬.
3. 앱 설정에서 **Use Tailscale subnets**(서브넷 사용)가 켜져 있는지.
4. 와이파이를 끄고 데이터로.

## 4. 확인
- PC 1: `& "C:\Program Files\Tailscale\tailscale.exe" status` — 휴대폰이 보이면 OK.
- 휴대폰(데이터): 브라우저로 `https://192.168.0.40:8443/api/v1/storefront/home`(인증서 경고는 LAN 사설 CA — ADR-0057) 또는 고객 앱이 열리면 OK.

## 5. 주의
- **PC 1 이 켜져 있고 Tailscale 이 실행 중**이어야 한다.
- 열린 곳은 `192.168.0.40` 하나뿐 — 게이트웨이만 쓰는 앱(고객·라이더 앱)은 되지만, 다른 IP 를 직접 부르는 화면(예 Keycloak `192.168.0.101` 직원 로그인)은 안 된다.
- 게이트웨이에는 Tailscale 로 들어온 휴대폰들이 PC 1 주소에서 온 것으로 보일 수 있다 → 비회원 처리율 제한(IP 당 초당 10)을 같이 쓴다. 여러 대가 동시에 시험하면 걸릴 수 있다.
- 데이터 사용량 — 상품 상세 하나에 이미지 약 1.4MB.
- PC 3 의 무선 adb(MFC 앱 설치, `docs/worklog/mfc/2026-11-13.md`)도 Tailscale 을 쓴다 — 다른 PC·기기라 이 계정 변경과는 따로다.

## 6. 내리기 (시험이 끝나면)
1. PC 1(관리자 PowerShell):
   ```powershell
   & "C:\Program Files\Tailscale\tailscale.exe" set --advertise-routes=
   ```
2. 관리 화면 → Machines → PC 1 → Edit route settings → `192.168.0.40/32` 체크 해제(사용자).
3. 필요 없으면 PC 1·휴대폰 Tailscale 로그아웃.
