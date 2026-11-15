# 2026-11-15 PC 1 → 다른 PC SSH 가 가끔 "Could not resolve hostname" — 컴퓨터 이름을 LLMNR 로만 찾음

- 범위: PC 1(배포 PC)의 Git Bash `ssh`·`scp` 가 `~/.ssh/config` 의 컴퓨터 이름(`HostName D603-13` 등)으로 PC 2~5 에 접속할 때. 배포(`lan.sh`), 원격 실행(`deploy/remote/run-ps.sh`), 감시(`watch.sh`) 모두 이 경로를 쓴다.
- 관련: `deploy/lan/README.md` "SSH 는 컴퓨터 이름으로 접속"·"IP 변경 시 복구", `deploy/lan/ipcheck.sh`, 작업 일지 `docs/worklog/platform/2026-11-15.md`, 커밋 d234e46b.

## 요약

| 증상 | 원인 | 해결 | 상태 |
|---|---|---|---|
| `ssh: Could not resolve hostname d603-13: Name or service not known`(오전에는 `ds603-12`) — 원격 명령·배포가 시작도 못 하고 실패. 바로 다시 하면 대개 됨 | 이 LAN 에는 PC 이름을 아는 DNS 가 없다. 이름은 **LLMNR/NetBIOS 방송**으로만 찾는데, 상대 PC 가 바쁘면(배포 중 컨테이너를 많이 다시 띄울 때) 대답이 늦어 실패한다 | `run-ps.sh`·`lan.sh copy_files` 가 **이름 해석 실패만** 3초 쉬고 최대 3번 다시 한다 | **완화**(2026-11-15). 근본 해결(이름을 DNS·hosts 로)은 하지 않음 — §4 |

## 1. 경과

1. 오전 PR #12·#14 배포 때 PC 2 첫 시도 `Could not resolve hostname ds603-12` → 다시 해서 성공.
2. 오후 감시·RAG 이동 뒤 점검 중 PC 5 원격 실행이 3번 연속 `d603-13` 이름 해석 실패. 같은 순간 Windows `Resolve-DnsName D603-13` 은 성공, `ping 192.168.0.101` 도 성공.
3. 조금 뒤 PC 2~5 에 `ssh exit` 를 48번(12회 × 4대) 해 보니 실패 0 — **늘 실패하는 것이 아니라 가끔**.

## 2. 왜 그렇게 됐나

- PC 1 의 DNS 서버는 인터넷 업체 것(`210.94.0.73`, `210.220.163.82`)이다. 이 서버들은 사내 PC 이름을 모른다 — `Resolve-DnsName D603-13 -DnsOnly` → "DNS 이름이 없습니다".
- 그래서 Windows 는 이름을 **LLMNR(멀티캐스트)·NetBIOS(브로드캐스트)** 로 찾는다 — `Resolve-DnsName -LlmnrNetbiosOnly` 는 성공. 이 방식은 "이 이름 누구냐"고 같은 네트워크에 물어 **상대 PC 가 제때 대답해야** 한다. 대답이 늦으면(짧은 대기 시간) 실패로 끝난다.
- 실패한 두 번 모두 그 PC 가 바쁠 때였다(배포 중 컨테이너 재생성·Docker 네트워크 재구성). 대답이 늦은 것으로 본다(정확한 패킷 기록은 없음 — 추정).
- Git Bash `ssh` 는 Windows 이름 해석(getaddrinfo)을 그대로 쓰므로 같은 영향을 받는다. Windows 가 잠깐 기억해 두는 결과(DNS 클라이언트 캐시)가 있으면 성공하고, 없을 때 새로 물으면 실패할 수 있다.
- 이름으로 접속하는 것은 일부러 고른 방식이다 — PC IP 가 바뀌어도 SSH 는 이어지고, `ipcheck.sh` 가 이름으로 실제 IP 를 찾아 `placement.conf` 와 다르면 알린다(`README` "IP 변경 시 복구"). 그래서 IP 로 바꾸지 않았다.

## 3. 해결(완화)

- `deploy/remote/run-ps.sh`: `ssh` 출력에 `Could not resolve hostname` 이 있고 종료 코드가 255 면 **3초 쉬고 다시(최대 3번)**. 다른 실패(인증·명령 오류 등)는 다시 하지 않는다. 3번 모두 실패하면 원래 오류를 내고 255 로 끝난다.
- `deploy/lan/lan.sh` `copy_files`: 원격 폴더 만들기(`ssh`)와 파일 복사(`scp`)도 같은 조건으로 다시.
- 다시 해도 안전한 이유: 이름을 못 찾으면 **연결 자체가 안 됐으므로 원격에서 아무것도 실행되지 않았다**.
- 시험: 가짜 `ssh`(두 번 이름 해석 실패 → 세 번째 성공)로 `run-ps.sh` 가 세 번째에 성공(rc 0), 늘 실패하면 오류 그대로(rc 255) 확인. 실제 `lan.sh status pc3` 정상.
- 감시(`watch.sh`)는 고치지 않았다 — 구성요소가 **연속 2번** 실패해야 재시작하므로 한 번의 이름 해석 실패로는 재시작하지 않는다. 같은 날 PC 1(이 PC)은 IP 확인에서 빼도록 고침(SSH 설정에 없어 "이름 조회 실패"로 나오던 것, d559c1ef).

## 4. 근본 해결 방법(하지 않음 — 필요해지면 고른다)

| 방법 | 장점 | 단점 |
|---|---|---|
| PC 1 `C:\Windows\System32\drivers\etc\hosts` 에 네 PC 이름·IP 적기 | 이름 해석이 방송 없이 바로 됨, 실패 없음 | 관리자 권한 필요. **IP 가 바뀌면 hosts 도 같이 고쳐야** 하고, 고치지 않으면 `ipcheck.sh` 가 옛 IP 를 "정상"으로 볼 수 있다 |
| 공유기 DHCP 예약 + 공유기 DNS 에 이름 등록 | IP 고정 + 이름 해석이 DNS 로 | 공유기 관리 권한이 필요(이 LAN 은 우리 것이 아님) |
| `~/.ssh/config` `HostName` 을 IP 로 | 이름 해석 없음 | IP 가 바뀌면 접속이 끊기고 `ipcheck.sh` 의 IP 변경 감지도 쓸 수 없다(이름 → IP 비교가 사라짐) |

- 지금은 실패가 드물고 다시 하면 되므로 §3 으로 충분하다고 봤다. 배포 중 이름 해석 실패가 잦아지면 hosts 방법을 먼저 검토한다.

## 5. 다시 생기면 확인할 것

```powershell
Resolve-DnsName D603-13 -LlmnrNetbiosOnly   # 방송으로 찾는가
Resolve-DnsName D603-13 -DnsOnly            # DNS 로는 원래 안 됨(정상)
Test-Connection 192.168.0.101 -Count 1      # 네트워크는 살아 있는가
```

- 방송으로도 못 찾으면: 그 PC 가 꺼졌거나, 네트워크 프로필이 "공용"으로 바뀌어 LLMNR·NetBIOS 응답이 막혔는지(그 PC 의 네트워크 설정) 본다.
- 이름은 찾는데 IP 가 `placement.conf` 와 다르면: IP 변경 — `README` "IP 변경 시 복구".
