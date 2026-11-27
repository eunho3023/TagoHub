# 이 PC 에서 Ubuntu 노트북으로 타볼까 국내 중계를 보내 설치함 (Windows 에 들어 있는 ssh·scp 사용)
#   powershell -ExecutionPolicy Bypass -File server\relay\linux\push-to-laptop.ps1 -Addr 192.168.0.23 -User tabolkka
# 비밀번호를 세 번 물어요: 파일 보내기(노트북 로그인), 접속(노트북 로그인), sudo(같은 비밀번호)
# 클라우드 서버(AWS Lightsail 서울 등)는 열쇠 파일로 접속: -KeyFile 로 .pem 경로를 주면 비밀번호를 묻지 않아요
#   powershell -ExecutionPolicy Bypass -File server\relay\linux\push-to-laptop.ps1 -Addr 3.35.x.x -User ubuntu -KeyFile $HOME\Downloads\LightsailDefaultKey-ap-northeast-2.pem
# 열쇠(RELAY_KEY)는 local.properties 에서 읽어 노트북의 /etc/tagohub-relay.env 로만 감 (화면에 안 보임)
param(
    [Parameter(Mandatory = $true)][string]$Addr,
    [Parameter(Mandatory = $true)][string]$User,
    [string]$KeyFile = ''
)
$ErrorActionPreference = 'Stop'
$sshOpt = @('-o', 'StrictHostKeyChecking=accept-new')
if ($KeyFile) {
    if (-not (Test-Path $KeyFile)) { throw "열쇠 파일이 없어요: $KeyFile" }
    $sshOpt += @('-i', (Resolve-Path $KeyFile).Path)
}
$here = $PSScriptRoot
$root = Resolve-Path (Join-Path $here '..\..\..')
$props = Join-Path $root 'local.properties'
$key = (Get-Content $props -Encoding UTF8 | Where-Object { $_ -match '^\s*RELAY_KEY\s*=' } | Select-Object -First 1)
if (-not $key) { throw 'local.properties 에 RELAY_KEY 가 없어요 (deploy.ps1 을 한 번 실행하면 생겨요)' }
$key = ($key -split '=', 2)[1].Trim()
# 예약 작업 열쇠: 있으면 노트북이 타볼까 예약 작업(/w/cron)도 1분마다 부름 (GitHub 예약이 몇 시간에 한 번꼴로만 돌아서)
$cron = (Get-Content $props -Encoding UTF8 | Where-Object { $_ -match '^\s*CRON_KEY\s*=' } | Select-Object -First 1)
$cron = if ($cron) { ($cron -split '=', 2)[1].Trim() } else { '' }

# 보낼 파일을 임시 폴더에 (줄 끝을 리눅스식 LF 로, BOM 없이)
$tmp = Join-Path $env:TEMP 'tagohub-relay'
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory $tmp | Out-Null
$utf8 = New-Object Text.UTF8Encoding $false
foreach ($f in @((Join-Path $here '..\relay.mjs'), (Join-Path $here 'install.sh'), (Join-Path $here 'tagohub-relay.service'))) {
    $text = [IO.File]::ReadAllText((Resolve-Path $f), [Text.Encoding]::UTF8) -replace "`r`n", "`n"
    [IO.File]::WriteAllText((Join-Path $tmp (Split-Path $f -Leaf)), $text, $utf8)
}
$envText = "RELAY_KEY=$key`n"
if ($cron) { $envText += "CRON_KEY=$cron`n" } else { Write-Host '(local.properties 에 CRON_KEY 가 없어 예약 작업 부르기는 빼고 보내요)' }
[IO.File]::WriteAllText((Join-Path $tmp 'relay.env'), $envText, $utf8)

try {
    Write-Host "1/2 서버($Addr)로 파일 보내는 중...$(if (-not $KeyFile) { ' (노트북 비밀번호)' })"
    & scp -r @sshOpt $tmp "${User}@${Addr}:"
    if ($LASTEXITCODE -ne 0) { throw '파일을 보내지 못했어요. 노트북 주소·아이디·비밀번호와 SSH 가 켜져 있는지 확인해 주세요' }
} finally {
    Remove-Item $tmp -Recurse -Force   # 열쇠가 든 임시 파일은 바로 지움
}

Write-Host "2/2 서버에서 설치하는 중...$(if (-not $KeyFile) { ' (노트북 비밀번호 → sudo 비밀번호)' })"
& ssh -t @sshOpt "${User}@${Addr}" 'sudo bash ~/tagohub-relay/install.sh'
if ($LASTEXITCODE -ne 0) { throw '설치 중 문제가 있었어요. 위 메시지를 확인해 주세요' }

Write-Host ''
$ans = Read-Host '노트북 중계가 "✅ 연결됐어요"로 나왔으면, 이 PC 의 중계를 끌까요? (y/n)'
if ($ans -eq 'y') {
    Unregister-ScheduledTask -TaskName 'TagoHub 국내 중계' -Confirm:$false -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -match 'relay\.mjs' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Get-Process cloudflared -ErrorAction SilentlyContinue | Stop-Process -Force
    Write-Host '이 PC 의 중계를 껐어요. 이제 노트북만 중계해요'
} else {
    Write-Host '이 PC 의 중계도 그대로 둬요 (두 곳이 번갈아 알려도 문제는 없어요)'
}
