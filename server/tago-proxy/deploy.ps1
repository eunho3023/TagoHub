# TagoHub 중계 서버 배포 (한 번만 실행). 브라우저 로그인 대신 Cloudflare API 토큰을 씀.
# 1) 서버를 올리고  2) 비밀 값 두 개(공공데이터포털 키, 앱 토큰)를 넣고  3) local.properties 에 서버 주소·앱 토큰을 적음.
# 키와 토큰은 화면에 보여 주지 않음. 공공데이터포털 키는 local.properties 의 TAGO_SERVICE_KEY 를 그대로 씀.
# npx 가 경고를 stderr 로 내도 멈추지 않게 (PowerShell 5.1 은 그것을 오류로 봄). 실패는 아래에서 직접 확인
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot
$props = Join-Path $PSScriptRoot '..\..\local.properties'
if (-not (Test-Path $props)) { throw 'local.properties 를 찾지 못했어요 (TagoHub 폴더에 있어야 함)' }

if (-not $env:CLOUDFLARE_API_TOKEN) {
    $sec = Read-Host 'Cloudflare API 토큰을 붙여 넣고 Enter (화면에는 안 보여요)' -AsSecureString
    $env:CLOUDFLARE_API_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}

$line = Get-Content $props -Encoding UTF8 | Where-Object { $_ -match '^\s*TAGO_SERVICE_KEY\s*=' } | Select-Object -First 1
if (-not $line) { throw 'local.properties 에 TAGO_SERVICE_KEY 가 없어요' }
$dataKey = ($line -split '=', 2)[1].Trim()

Write-Host '1/3 서버 올리는 중...'
$out = (& npx --yes wrangler@4.45.0 deploy 2>&1 | Out-String)
Write-Host $out
$m = [regex]::Match($out, 'https://[A-Za-z0-9.-]+\.workers\.dev')
if (-not $m.Success) {
    if ($out -match 'workers\.dev subdomain') { Write-Host 'workers.dev 주소 이름을 아직 안 정했어요: https://dash.cloudflare.com 에서 Workers & Pages 를 열고 주소 이름(subdomain)을 정한 뒤 다시 실행해 주세요' }
    throw '서버 주소를 찾지 못했어요. 위 메시지를 확인해 주세요'
}
$url = $m.Value

Write-Host '2/3 비밀 값 넣는 중...'
$appToken = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$dataKey | & npx --yes wrangler@4.45.0 secret put DATA_GO_KR_KEY | Out-Null
$appToken | & npx --yes wrangler@4.45.0 secret put APP_TOKEN | Out-Null

Write-Host '3/3 local.properties 에 서버 주소 적는 중...'
$keep = Get-Content $props -Encoding UTF8 | Where-Object { $_ -notmatch '^\s*TAGO_PROXY_(URL|TOKEN)\s*=' }
$keep + "TAGO_PROXY_URL=$url" + "TAGO_PROXY_TOKEN=$appToken" | Set-Content $props -Encoding UTF8

Start-Sleep -Seconds 3
try { $h = (Invoke-WebRequest "$url/health" -UseBasicParsing).Content } catch { $h = $_.Exception.Message }
Write-Host "끝: $url (상태: $h)"
