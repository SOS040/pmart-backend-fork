# 새 LAN PC 처음 준비(그 PC 에서 관리자 PowerShell 로 한 번 실행, 또는 PC 1 에서 비밀번호 SSH 로 — README). PC 1 이 SSH 로 접속할 수 있게만 한다.
# 나머지(sshd 기본 셸 외 Docker 방화벽·.wslconfig·예약 작업·절전·compose 경로)는 PC 1 에서 SSH 로 맞춘다 — deploy/lan/README.md "새 PC 추가".
# 들어 있는 키는 PC 1 LAN 키(~/.ssh/pmart_lan.pem)의 공개 키다(비밀 아님).
$ErrorActionPreference = 'Stop'
$key = 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQDVm/L3T2dmO9VGSeacSGIyQRDHEWLnA5o9unm1Mm3BD73qOQgUi2v0hWbDy/Y1/ix5qTXybe2h1pP+91IVFloTmGtZC3Awb+bsYv3/diHV/j7sBhAHkxZEF+ytMyZv84G0kUwtpPsOblphzjwUls73OjVS1b7NTC1DBFL04VUGBDnTvcc5rdpWWfpswcScI47an8vwwBCBkTJ74GhubPzpwHRaFUrJTpmZ7G5EkQa9fUP1lSu7gp/yUoIY90kvRilr9p5xgyXh/tlLEUEkq0ukL0u8dAXIq1e6cyX6ZzheDvKgTWCeJ2YIBbE0wT5nKzhEywIvVvLj5wrj4/lxniRG2SwRAPpuEnxiDdH51tChERx2KouAIZH/MDa0J6G3GJYIH0C9tubkLuD2Nq6boZ2tPFyBGeUQPyDvHJ4+5geU9SOvtr51NpiYTnF0OtzWpIlqFmB6HC9ozFibWN1QEHP2QYsTAxmDgeiCwhV1RhASnmbcOFuFZvyppNF/l9O12EcoFpoqop87Z9OlgIxMx7JCbqun6i1gvtfH9zlPEoxHau3qbxB4yD0TiDW8dU1wij+1YoSnL7WJQX8zMHU4fesR3gR/yuftiUH2ioYW0HwHOyS28+tr4qYEqBKk7fi3ETu5hYxzG3ahOW4tqs6dqZ3iOBd7sYFSC1qreDHQ3rENjw== pmart-lan'

# 1. OpenSSH 서버
if ((Get-WindowsCapability -Online -Name 'OpenSSH.Server*').State -ne 'Installed') {
  Add-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' | Out-Null
}
Set-Service sshd -StartupType Automatic
Start-Service sshd

# 2. 관리자 계정용 공개 키(이 키 하나만)
$ak = "$env:ProgramData\ssh\administrators_authorized_keys"
Set-Content -Path $ak -Value $key -Encoding ascii
icacls $ak /inheritance:r /grant 'Administrators:F' /grant 'SYSTEM:F' | Out-Null

# 3. SSH 기본 셸 = Windows PowerShell(cmd 는 명령줄 8,000자 제한 — deploy/remote/run-ps.sh)
New-ItemProperty -Path 'HKLM:\SOFTWARE\OpenSSH' -Name DefaultShell -Value 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -PropertyType String -Force | Out-Null

# 4. 방화벽: LAN 에서 SSH·ping
foreach ($r in @(
  @{ n = 'PMart SSH (LAN)'; p = 'TCP'; port = 22 },
  @{ n = 'PMart ping (LAN)'; p = 'ICMPv4'; port = $null })) {
  Get-NetFirewallRule -DisplayName $r.n -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  if ($r.p -eq 'TCP') {
    New-NetFirewallRule -DisplayName $r.n -Direction Inbound -Action Allow -Protocol TCP -LocalPort $r.port -RemoteAddress 192.168.0.0/24 | Out-Null
  } else {
    New-NetFirewallRule -DisplayName $r.n -Direction Inbound -Action Allow -Protocol ICMPv4 -IcmpType 8 -RemoteAddress 192.168.0.0/24 | Out-Null
  }
}

# 5. PC 1 에 알려 줄 값
"컴퓨터 이름: $env:COMPUTERNAME"
"사용자: $env:USERNAME (관리자: $(([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrators')))"
"IPv4: $((Get-NetIPAddress -AddressFamily IPv4 | ? { $_.IPAddress -like '192.168.0.*' }).IPAddress)"
"sshd: $((Get-Service sshd).Status)"

# 6. 기본 셸 반영 — 맨 끝(SSH 로 이 스크립트를 돌릴 때 접속이 끊겨도 앞 단계는 끝나 있게)
Restart-Service sshd
