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
} else {
    Write-Host '  파이썬이 없어서 자료 조각은 건너뜀 (웹·PC 버전의 정류장 찾기만 빠짐)'
}

Write-Host '준비: 웹 버전(화면) 만드는 중...'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$pub = Join-Path $PSScriptRoot 'public'
if (-not $env:JAVA_HOME) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
}
& (Join-Path $root 'gradlew.bat') -p $root -q ':composeApp:wasmJsBrowserDistribution'
$dist = Join-Path $root 'composeApp\build\dist\wasmJs\productionExecutable'
if (Test-Path (Join-Path $dist 'index.html')) {
    # 예전 빌드 파일 지우고 새로 (자료 조각 data, 글꼴 fonts, _headers 는 그대로)
    Get-ChildItem $pub -File | Where-Object { $_.Name -ne '_headers' } | Remove-Item -Force
    if (Test-Path (Join-Path $pub 'composeResources')) { Remove-Item (Join-Path $pub 'composeResources') -Recurse -Force }
    Get-ChildItem $dist | Where-Object { $_.Name -notlike '*.map' } | Copy-Item -Destination $pub -Recurse -Force
    # 한글 글꼴 (웹 화면은 브라우저 글꼴을 못 씀): 원본 10MB 에서 필요한 글자만 남긴 작은 파일 (pipeline/build_web_font.py)
    $font = Join-Path $pub 'fonts\NotoSansKR-sub.ttf'
    $oldFont = Join-Path $pub 'fonts\NotoSansKR.ttf'
    if (Test-Path $oldFont) { Remove-Item $oldFont -Force }
    if ($py) {
        & $py -c "import fontTools" 2>$null
        if ($LASTEXITCODE -ne 0) { & $py -m pip install --user -q fonttools }
        & $py (Join-Path $root 'pipeline\build_web_font.py') --data (Join-Path $pub 'data') --out $font
    }
    if (-not (Test-Path $font)) { Write-Host '  한글 글꼴을 만들지 못했어요 (웹 화면의 한글이 네모로 보일 수 있음)' }
    else {
        # 글꼴은 브라우저가 1년 저장하므로, 내용이 바뀌면 주소(?v=)도 바뀌게 해서 새 글꼴을 받게 함
        $ver = (Get-FileHash $font -Algorithm MD5).Hash.Substring(0, 10).ToLower()
        $page = Join-Path $pub 'index.html'
        $html = [IO.File]::ReadAllText($page, [Text.Encoding]::UTF8)
        $html = $html.Replace('fonts/NotoSansKR-sub.ttf"', "fonts/NotoSansKR-sub.ttf?v=$ver`"")
        [IO.File]::WriteAllText($page, $html, (New-Object Text.UTF8Encoding $false))
    }
    # 카카오 지도 JavaScript 키 (local.properties 의 KAKAO_JS_KEY). 웹 페이지에 들어가는 키라 공개돼도 되는 종류이고, 카카오에 등록한 도메인에서만 동작
    # 키가 없으면 빈 값 → 웹 버전은 OpenStreetMap 지도
    $jsKey = (Prop 'KAKAO_JS_KEY') -replace '[^A-Za-z0-9]', ''
    [IO.File]::WriteAllText((Join-Path $pub 'config.js'), "window.TAGOHUB_KAKAO_JS_KEY = `"$jsKey`";`n", (New-Object Text.UTF8Encoding $false))
    if ($jsKey) { Write-Host '  카카오 지도 키 넣음 (카카오 개발자 사이트에서 이 서버 주소를 JavaScript SDK 도메인으로 등록해야 지도가 보임)' } else { Write-Host '  카카오 지도 키 없음 → OpenStreetMap 지도' }
    Write-Host '  웹 버전 준비됨 (서버 주소로 열면 보임)'
} else {
    Write-Host '  웹 버전을 만들지 못해 건너뜀 (서버는 그대로 올림)'
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
