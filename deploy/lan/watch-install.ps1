# PC 1 작업 스케줄러에 LAN 감시(deploy/lan/watch.sh)를 5분 간격으로 등록한다. 다시 실행하면 덮어쓴다.
#   powershell -ExecutionPolicy Bypass -File deploy/lan/watch-install.ps1            등록(현재 사용자, 로그온 중일 때 실행)
#   powershell -ExecutionPolicy Bypass -File deploy/lan/watch-install.ps1 -S4U       로그온 여부와 무관하게 실행(관리자 PowerShell 필요)
#   powershell -ExecutionPolicy Bypass -File deploy/lan/watch-install.ps1 -Remove    해제
# 창이 뜨지 않게 conhost --headless 로 Git Bash 를 띄운다. ssh 키(~/.ssh/pmart_lan)는 같은 사용자 프로필에서 읽는다.
param([switch]$Remove, [switch]$S4U, [int]$IntervalMinutes = 5)
$ErrorActionPreference = 'Stop'
$name = 'PMart LAN Watch'
if ($Remove) { Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue; "removed: $name"; return }

$bash = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path $bash)) { throw "Git Bash 없음: $bash" }
$win = (Resolve-Path (Join-Path $PSScriptRoot 'watch.sh')).Path
$script = '/' + $win.Substring(0, 1).ToLower() + ($win.Substring(2) -replace '\\', '/')   # C:\a\b → /c/a/b

$action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\conhost.exe" -Argument "--headless `"$bash`" -lc `"$script`""
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes)
$logon = if ($S4U) { 'S4U' } else { 'Interactive' }
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType $logon
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 4) -StartWhenAvailable
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
"registered: $name every $IntervalMinutes min ($logon) -> $script"
