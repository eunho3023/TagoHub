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
$d1Err = Join-Path $env:TEMP 'tagohub-d1.err'
# 데이터베이스 목록 (못 받으면 $null, 하나도 없으면 빈 목록). 화면 안내 줄이 섞여도 끝의 JSON 만 읽음
function D1List {
    $raw = (& npx --yes wrangler@4.45.0 d1 list --json 2>$d1Err | Out-String)
    $m = [regex]::Match($raw, '(?s)(?:^|\n)\s*(\[.*\])\s*$')
    if (-not $m.Success) { $script:d1Why = $raw + (Get-Content $d1Err -Raw -ErrorAction SilentlyContinue); return $null }
    try { $parsed = ConvertFrom-Json $m.Groups[1].Value } catch { $script:d1Why = $raw; return $null }
    return ,@($parsed | ForEach-Object { $_ })
}
function FindLog($list) { return $list | Where-Object { $_.name -eq 'tagohub-log' } | Select-Object -First 1 }
$d1Why = ''
$dbs = D1List
$db = $null
if ($null -ne $dbs) {
    $db = FindLog $dbs
    if (-not $db) {
        Write-Host '  처음이라 새로 만드는 중...'
        $made = (& npx --yes wrangler@4.45.0 d1 create tagohub-log 2>&1 | Out-String)
        $again = D1List
        if ($null -ne $again) { $db = FindLog $again }
        if (-not $db) { $d1Why = $made }
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
    Write-Host '  데이터베이스를 만들지 못했어요. 지난 운행 기록 모으기만 빼고 서버는 그대로 올려요. 이유:'
    # wrangler 메시지에서 오류 줄만 (키·토큰은 들어 있지 않음)
    ($d1Why -split "`n" | Where-Object { $_ -match 'ERROR|error|Authentication|permission|code:|10000|7403' } | Select-Object -First 6) | ForEach-Object { Write-Host "    $_" }
    Write-Host '  "Authentication error" 나 권한 이야기가 나오면: API 토큰에 Account - D1 - Edit 권한을 더한 뒤 다시 실행'
}

Write-Host '준비: 정류장·노선 자료 조각 만드는 중 (웹·PC 버전용, 최신 DB 를 받아서)...'
# 파이썬 찾기 (윈도우 스토어 가짜 python 은 건너뜀)
$py = $null
foreach ($p in @("$env:LOCALAPPDATA\Python\pythoncore-*\python.exe", "$env:LOCALAPPDATA\Programs\Python\Python3*\python.exe", "C:\Program Files\Python3*\python.exe")) {
    $found = Get-ChildItem $p -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
    if ($found) { $py = $found.FullName; break }
}
if (-not $py) {
    $cmd = Get-Command python -All -ErrorAction SilentlyContinue | Where-Object { $_.Source -notmatch 'WindowsApps' } | Select-Object -First 1
    if ($cmd) { $py = $cmd.Source }
}
if ($py) {
    $env:PYTHONIOENCODING = 'utf-8'
    & $py (Join-Path $PSScriptRoot '..\..\pipeline\build_web_data.py') --out (Join-Path $PSScriptRoot 'public\data')
    # 클라우드 서버 접속을 막는 지역(천안·구미)의 공식 배차시간표는 이 컴퓨터에서 받아 같이 올림 (실패해도 계속)
    # 7일에 한 번만 새로 받고 그 사이에는 pipeline/cache/times 에 보관한 것을 씀
    Write-Host '준비: 천안·구미 버스 배차시간표 (7일마다 새로 받음, 받을 때는 2~3분)...'
    & $py (Join-Path $PSScriptRoot '..\..\pipeline\fetch_city_times.py') --out (Join-Path $PSScriptRoot 'public\data')
} else {
    Write-Host '  파이썬이 없어서 자료 조각은 건너뜀 (웹·PC 버전의 정류장 찾기만 빠짐)'
}

# 웹 화면은 타볼까(Cloudflare Pages)에만 올림 (아래 4/4). 예전 서버(workers.dev)에는 자료 조각(data)과 _headers 만:
# 화면 주소로 들어오면 서버 코드가 타볼까로 넘김 (src/index.js)
$pub = Join-Path $PSScriptRoot 'public'
Get-ChildItem $pub -File | Where-Object { $_.Name -ne '_headers' } | Remove-Item -Force
Get-ChildItem $pub -Directory | Where-Object { $_.Name -ne 'data' } | Remove-Item -Recurse -Force
# 카카오 지도 JavaScript 키 (local.properties 의 KAKAO_JS_KEY). 웹 페이지에 들어가는 키라 공개돼도 되는 종류이고, 카카오에 등록한 도메인에서만 동작
# 키가 없으면 빈 값 → 웹 버전은 OpenStreetMap 지도
$jsKey = (Prop 'KAKAO_JS_KEY') -replace '[^A-Za-z0-9]', ''

Write-Host '1/4 예전 서버(이미 깔린 앱용) 올리는 중...'
$out = (& npx --yes wrangler@4.45.0 deploy --config $config 2>&1 | Out-String)
Write-Host $out
$m = [regex]::Match($out, 'https://[A-Za-z0-9.-]+\.workers\.dev')
if (-not $m.Success) {
    if ($out -match 'workers\.dev subdomain') { Write-Host 'workers.dev 주소 이름을 아직 안 정했어요: https://dash.cloudflare.com 에서 Workers & Pages 를 열고 주소 이름(subdomain)을 정한 뒤 다시 실행해 주세요' }
    throw '서버 주소를 찾지 못했어요. 위 메시지를 확인해 주세요'
}
$url = $m.Value

# 타볼까 (Cloudflare Pages): 화면·자료·서버 코드 모두 (pages/functions 가 같은 src/index.js 를 돌림)
# API 토큰에 "Cloudflare Pages: Edit" 권한이 있어야 함
$pagesProject = 'tabolkka'
$pagesUrl = "https://$pagesProject.pages.dev"
$pagesRoot = Join-Path $PSScriptRoot 'pages'
Push-Location $pagesRoot
$null = (& npx --yes wrangler@4.45.0 pages project create $pagesProject --production-branch main 2>&1 | Out-String)
Pop-Location
# 비밀 값은 예전 서버와 타볼까 두 곳에 같이 (값은 화면에 쓰지 않음)
function PutSecret([string]$name, [string]$value) {
    if (-not $value) { return }
    $value | & npx --yes wrangler@4.45.0 secret put $name --config $config | Out-Null
    Push-Location $pagesRoot
    $value | & npx --yes wrangler@4.45.0 pages secret put $name --project-name $pagesProject | Out-Null
    Pop-Location
}

Write-Host '2/4 비밀 값 넣는 중 (예전 서버·타볼까)...'
$appToken = Prop 'TAGO_PROXY_TOKEN'
if (-not $appToken) { $appToken = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N') }
PutSecret 'APP_TOKEN' $appToken
PutSecret 'DATA_GO_KR_KEY' $dataKey
# 앱이 자리표시(TAGOHUB_<이름>)로 보내는 키들 (src/proxy.js 의 KEY_NAMES). 비어 있는 것은 건너뜀
$fallback = @{ 'KLID_BUS_KEY' = 'TAGO_SERVICE_KEY'; 'KAC_API_KEY' = 'TAGO_SERVICE_KEY' }
foreach ($name in @('TAGO_SERVICE_KEY', 'KLID_BUS_KEY', 'KAC_API_KEY', 'KRIC_SUBWAY_KEY', 'SEOUL_SIGNAL_KEY', 'EX_API_KEY', 'SEOUL_SUBWAY_KEY', 'KAKAO_REST_KEY', 'KMA_HUB_KEY', 'KEPCO_API_KEY', 'DEV_IDS')) {
    $v = Prop $name
    if (-not $v -and $fallback.ContainsKey($name)) { $v = Prop $fallback[$name] }
    if ($v) {
        PutSecret $name $v
        Write-Host "  $name 넣음"
    } else {
        Write-Host "  $name 없음 (건너뜀)"
    }
}

# 지도 고치기 관리자 키 (노선 모양·정류장·역 위치를 고칠 수 있는 사람만 아는 값). local.properties 의 EDIT_ADMIN_KEY,
# 없으면 새로 만들어 local.properties 에 적음 (화면에는 안 보임 → 앱·웹 설정의 "관리자 키"에 local.properties 의 값을 붙여 넣음)
$adminKey = Prop 'EDIT_ADMIN_KEY'
if (-not $adminKey) {
    $adminKey = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    # 마지막 줄 끝에 줄바꿈이 없어도 붙지 않게 줄 목록으로 다시 씀
    @(Get-Content $props -Encoding UTF8) + "EDIT_ADMIN_KEY=$adminKey" | Set-Content $props -Encoding UTF8
    Write-Host '  지도 고치기 관리자 키를 새로 만들어 local.properties 의 EDIT_ADMIN_KEY 에 적었어요'
}
PutSecret 'EDIT_ADMIN_KEY' $adminKey
Write-Host '  지도 고치기 관리자 키 넣음'

# 예약 작업 열쇠: GitHub Actions(.github/workflows/server-cron.yml)가 1분마다 타볼까 /w/cron 을 부를 때 씀.
# 없으면 새로 만들어 local.properties 의 CRON_KEY 에 적음 → 같은 값을 GitHub 저장소 Settings → Secrets → Actions 의 CRON_KEY 에 넣어야 함
$cronKey = Prop 'CRON_KEY'
if (-not $cronKey) {
    $cronKey = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    @(Get-Content $props -Encoding UTF8) + "CRON_KEY=$cronKey" | Set-Content $props -Encoding UTF8
    Write-Host '  예약 작업 열쇠를 새로 만들어 local.properties 의 CRON_KEY 에 적었어요 (GitHub 비밀값 CRON_KEY 에도 같은 값을 넣어 주세요)'
}
PutSecret 'CRON_KEY' $cronKey
Write-Host '  예약 작업 열쇠 넣음'

# 웹 버전 앱 제보 → 폰 앱과 같은 Firebase(Firestore reports). app/google-services.json 에서 (값은 화면에 쓰지 않음)
$gs = Join-Path $PSScriptRoot '..\..\app\google-services.json'
if (Test-Path $gs) {
    $g = Get-Content $gs -Raw -Encoding UTF8 | ConvertFrom-Json
    $client = @($g.client) | Select-Object -First 1
    $fbKey = (@($client.api_key) | Select-Object -First 1).current_key
    $fbCert = (@($client.oauth_client) | Where-Object { $_.android_info } | Select-Object -First 1).android_info.certificate_hash
    if ($fbKey) {
        PutSecret 'FIREBASE_API_KEY' $fbKey
        PutSecret 'FIREBASE_PROJECT_ID' $g.project_info.project_id
        if ($fbCert) { PutSecret 'FIREBASE_ANDROID_CERT' $fbCert }
        Write-Host '  Firebase 제보 설정 넣음'
    }
} else {
    Write-Host '  app/google-services.json 없음 → 웹 제보는 깃허브로'
}

Write-Host '3/4 local.properties 에 서버 주소(타볼까) 적는 중...'
$keep = Get-Content $props -Encoding UTF8 | Where-Object { $_ -notmatch '^\s*TAGO_PROXY_(URL|TOKEN)\s*=' }
# 새로 빌드하는 앱은 타볼까 주소를 씀 (이미 깔린 예전 앱은 예전 서버 주소 그대로 — 그 서버도 계속 돎)
$keep + "TAGO_PROXY_URL=$pagesUrl" + "TAGO_PROXY_TOKEN=$appToken" | Set-Content $props -Encoding UTF8

Write-Host "4/4 타볼까 올리는 중 ($pagesUrl)..."
# dist = 화면(site) + 자료 조각(public/data) + 카카오 키(config.js) + _headers + _routes.json(서버 길만 서버 코드로)
$dist = Join-Path $pagesRoot 'dist'
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory $dist | Out-Null
Get-ChildItem (Join-Path $PSScriptRoot 'site') | Copy-Item -Destination $dist -Recurse -Force
if (Test-Path (Join-Path $pub 'data')) { Copy-Item (Join-Path $pub 'data') -Destination $dist -Recurse -Force }
Copy-Item (Join-Path $pub '_headers') -Destination $dist -Force
Copy-Item (Join-Path $pagesRoot '_routes.json') -Destination $dist -Force
[IO.File]::WriteAllText((Join-Path $dist 'config.js'), "window.TAGOHUB_KAKAO_JS_KEY = `"$jsKey`";`n", (New-Object Text.UTF8Encoding $false))
# Pages 설정 (데이터베이스 번호가 들어가 저장소에는 안 올림: .gitignore)
$ptoml = "name = `"$pagesProject`"`npages_build_output_dir = `"./dist`"`ncompatibility_date = `"2025-10-01`"`n"
if ($db -and $db.uuid) { $ptoml += "`n[[d1_databases]]`nbinding = `"DB`"`ndatabase_name = `"tagohub-log`"`ndatabase_id = `"$($db.uuid)`"`n" }
[IO.File]::WriteAllText((Join-Path $pagesRoot 'wrangler.toml'), $ptoml, (New-Object Text.UTF8Encoding $false))
Push-Location $pagesRoot
$pout = (& npx --yes wrangler@4.45.0 pages deploy dist --project-name $pagesProject --branch main --commit-dirty=true 2>&1 | Out-String)
Pop-Location
Write-Host $pout
if ($pout -match 'Authentication error|10000') { Write-Host '  Pages 권한이 없어요: Cloudflare API 토큰에 "Cloudflare Pages: Edit" 권한을 더한 뒤 다시 실행해 주세요' }
if ($jsKey) { Write-Host "  카카오 지도: 카카오 개발자 사이트 JavaScript SDK 도메인에 $pagesUrl 이 있어야 지도가 보임" }

Start-Sleep -Seconds 3
try { $h = (Invoke-WebRequest "$pagesUrl/health" -UseBasicParsing).Content } catch { $h = $_.Exception.Message }
Write-Host "끝: $pagesUrl (상태: $h) · 이미 깔린 예전 앱용 서버: $url"
