# 타볼까 국내 중계를 이 PC 에 켜 둠: 로그인할 때와 5분마다 start-relay.ps1 을 부르는 작업 스케줄러 작업 "TagoHub 국내 중계" 를 만들고 지금 바로 켬
# 끄려면: Unregister-ScheduledTask -TaskName 'TagoHub 국내 중계' -Confirm:$false ; Get-Process node | Where-Object { $_.CommandLine -match 'relay.mjs' } | Stop-Process
$name = 'TagoHub 국내 중계'
$start = Join-Path $PSScriptRoot 'start-relay.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$start`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
# 5분마다도 불러서, 중계가 꺼져 있으면 다시 켬 (돌고 있으면 start-relay.ps1 이 바로 끝남)
$every = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
$trigger = @($trigger, $every)
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Description '타볼까 서버가 공공데이터포털에 물을 때 이 PC 를 거치게 함 (server/relay/relay.mjs)' -Force | Out-Null
Start-ScheduledTask -TaskName $name
Write-Host "'$name' 등록하고 켰어요. 기록: server\relay\logs"
