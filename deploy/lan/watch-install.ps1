# PC 1 작업 스케줄러에 LAN 감시(deploy/lan/watch.sh)를 5분 간격으로 등록한다. 다시 실행하면 덮어쓴다.
#   powershell -ExecutionPolicy Bypass -File deploy/lan/watch-install.ps1            등록
#   powershell -ExecutionPolicy Bypass -File deploy/lan/watch-install.ps1 -Remove    해제
# S4U(로그온 여부와 무관, 창 없음)로 등록한다. ssh 키(~/.ssh/pmart_lan)는 같은 사용자 프로필에서 읽는다.
param([switch]$Remove, [int]$IntervalMinutes = 5)
$name = 'PMart LAN Watch'
if ($Remove) { Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue; "removed: $name"; return }

$bash = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path $bash)) { throw "Git Bash 없음: $bash" }
$script = (Resolve-Path (Join-Path $PSScriptRoot 'watch.sh')).Path -replace '\\','/' -replace '^([A-Za-z]):', { '/' + $_.Groups[1].Value.ToLower() }

$action = New-ScheduledTaskAction -Execute $bash -Argument "-lc `"$script`""
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes)
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType S4U
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 4) -StartWhenAvailable
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
"registered: $name every $IntervalMinutes min → $script"
