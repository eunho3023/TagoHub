# TagoHub 중계 서버 배포 (한 번만 실행). 브라우저 로그인 대신 Cloudflare API 토큰을 씀.
# 1) 서버를 올리고  2) 비밀 값(앱 토큰, local.properties 의 API 키들)을 넣고  3) local.properties 에 서버 주소·앱 토큰을 적음.
# 다시 실행해도 됨: 앱 토큰은 local.properties 에 있으면 그대로 씀 (앱을 다시 빌드하지 않아도 되게).
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

$lines = Get-Content $props -Encoding UTF8
function Prop([string]$name) {
    $l = $lines | Where-Object { $_ -match ('^\s*' + $name + '\s*=') } | Select-Object -First 1
    if ($l) { return ($l -split '=', 2)[1].Trim() } else { return '' }
}
$dataKey = Prop 'TAGO_SERVICE_KEY'
if (-not $dataKey) { throw 'local.properties 에 TAGO_SERVICE_KEY 가 없어요' }

Write-Host '준비: 지난 운행 기록 데이터베이스(D1, 이름 tagohub-log) 확인 중...'
function D1List {
    $j = (& npx --yes wrangler@4.45.0 d1 list --json 2>$null | Out-String)
    try { return @($j | ConvertFrom-Json) } catch { return $null }
}
$dbs = D1List
$db = $null
if ($dbs -ne $null) {
    $db = $dbs | Where-Object { $_.name -eq 'tagohub-log' } | Select-Object -First 1
    if (-not $db) {
        & npx --yes wrangler@4.45.0 d1 create tagohub-log 2>&1 | Out-Null
        $db = D1List | Where-Object { $_.name -eq 'tagohub-log' } | Select-Object -First 1
    }
}
$config = 'wrangler.toml'
if ($db -and $db.uuid) {
    # 데이터베이스 번호를 넣은 배포용 설정 (저장소에는 안 올림: .gitignore)
    $toml = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'wrangler.toml'), [Text.Encoding]::UTF8)
    $toml += "`n[[d1_databases]]`nbinding = `"DB`"`ndatabase_name = `"tagohub-log`"`ndatabase_id = `"$($db.uuid)`"`n"
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'wrangler.deploy.toml'), $toml, (New-Object Text.UTF8Encoding $false))
    $config = 'wrangler.deploy.toml'
    Write-Host '  데이터베이스 있음'
} else {
    Write-Host '  데이터베이스를 만들지 못했어요: API 토큰에 "D1 - 편집(Edit)" 권한을 더해 주세요. 지난 운행 기록 모으기만 빼고 서버는 그대로 올려요'
}

Write-Host '1/3 서버 올리는 중...'
$out = (& npx --yes wrangler@4.45.0 deploy --config $config 2>&1 | Out-String)
Write-Host $out
$m = [regex]::Match($out, 'https://[A-Za-z0-9.-]+\.workers\.dev')
if (-not $m.Success) {
    if ($out -match 'workers\.dev subdomain') { Write-Host 'workers.dev 주소 이름을 아직 안 정했어요: https://dash.cloudflare.com 에서 Workers & Pages 를 열고 주소 이름(subdomain)을 정한 뒤 다시 실행해 주세요' }
    throw '서버 주소를 찾지 못했어요. 위 메시지를 확인해 주세요'
}
$url = $m.Value

Write-Host '2/3 비밀 값 넣는 중...'
$appToken = Prop 'TAGO_PROXY_TOKEN'
if (-not $appToken) { $appToken = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N') }
$appToken | & npx --yes wrangler@4.45.0 secret put APP_TOKEN | Out-Null
$dataKey | & npx --yes wrangler@4.45.0 secret put DATA_GO_KR_KEY | Out-Null
# 앱이 자리표시(TAGOHUB_<이름>)로 보내는 키들 (src/proxy.js 의 KEY_NAMES). 비어 있는 것은 건너뜀
$fallback = @{ 'KLID_BUS_KEY' = 'TAGO_SERVICE_KEY'; 'KAC_API_KEY' = 'TAGO_SERVICE_KEY' }
foreach ($name in @('TAGO_SERVICE_KEY', 'KLID_BUS_KEY', 'KAC_API_KEY', 'KRIC_SUBWAY_KEY', 'SEOUL_SIGNAL_KEY', 'EX_API_KEY', 'SEOUL_SUBWAY_KEY', 'KAKAO_REST_KEY')) {
    $v = Prop $name
    if (-not $v -and $fallback.ContainsKey($name)) { $v = Prop $fallback[$name] }
    if ($v) {
        $v | & npx --yes wrangler@4.45.0 secret put $name | Out-Null
        Write-Host "  $name 넣음"
    } else {
        Write-Host "  $name 없음 (건너뜀)"
    }
}

Write-Host '3/3 local.properties 에 서버 주소 적는 중...'
$keep = Get-Content $props -Encoding UTF8 | Where-Object { $_ -notmatch '^\s*TAGO_PROXY_(URL|TOKEN)\s*=' }
$keep + "TAGO_PROXY_URL=$url" + "TAGO_PROXY_TOKEN=$appToken" | Set-Content $props -Encoding UTF8

Start-Sleep -Seconds 3
try { $h = (Invoke-WebRequest "$url/health" -UseBasicParsing).Content } catch { $h = $_.Exception.Message }
Write-Host "끝: $url (상태: $h)"
