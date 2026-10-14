# TAGO 지역 버스 노선 데이터 받기 (TagoHub 앱 안 데이터용) — 앱에 노선 데이터가 없는 70개 지역
# 실행: 프로젝트 폴더에서  powershell -ExecutionPolicy Bypass -File tools\fetch_tago.ps1
# 결과: tools\tago_data.zip  ← 이 파일을 보내주세요
# - 인증키는 local.properties 의 TAGO_SERVICE_KEY 에서 읽음 (이 파일에 키 없음)
# - TAGO 개발계정은 하루 호출 한도가 있음 → 한도에 걸리면 멈추고, 다음 날 다시 실행하면 받은 것은 건너뛰고 이어서 받음
# - 순서: 큰 도시부터 / 노선 목록 → 노선별 정류장 순서 → 노선 정보(첫차·막차·배차)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $PSScriptRoot
$props = Join-Path $root "local.properties"
if (-not (Test-Path $props)) { Write-Host "local.properties 를 찾지 못했습니다: $props"; exit 1 }
$line = Get-Content $props -Encoding UTF8 | Where-Object { $_ -match '^\s*TAGO_SERVICE_KEY\s*=' } | Select-Object -First 1
if (-not $line) { Write-Host "local.properties 에 TAGO_SERVICE_KEY 가 없습니다"; exit 1 }
$raw = ($line -split '=', 2)[1].Trim()
$key = if ($raw -match '%') { $raw } else { [uri]::EscapeDataString($raw) }

$base = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService"
$out = Join-Path $PSScriptRoot "tago_data"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$script:calls = 0

# 앱에 노선 데이터가 없는 지역 (앱 정류장 DB 기준, 정류장 많은 순)
$targets = @(
    @("21", "부산광역시"),
    @("22", "대구광역시"),
    @("37100", "경상북도 경산시"),
    @("24", "광주광역시"),
    @("26", "울산광역시"),
    @("38010", "경상남도 창원시"),
    @("33010", "충청북도 청주시"),
    @("25", "대전광역시"),
    @("34020", "충청남도 공주시"),
    @("38070", "경상남도 김해시"),
    @("34010", "충청남도 천안시"),
    @("34040", "충청남도 아산시"),
    @("37040", "경상북도 안동시"),
    @("36030", "전라남도 순천시"),
    @("36040", "전라남도 나주시"),
    @("37050", "경상북도 구미시"),
    @("33020", "충청북도 충주시"),
    @("37070", "경상북도 영천시"),
    @("34050", "충청남도 서산시"),
    @("38030", "경상남도 진주시"),
    @("36020", "전라남도 여수시"),
    @("38100", "경상남도 양산시"),
    @("34380", "충청남도 태안군"),
    @("37020", "경상북도 경주시"),
    @("36060", "전라남도 광양시"),
    @("32010", "강원특별자치도 춘천시"),
    @("33030", "충청북도 제천시"),
    @("35020", "전라북도 군산시"),
    @("34030", "충청남도 보령시"),
    @("37030", "경상북도 김천시"),
    @("35030", "전라북도 익산시"),
    @("34350", "충청남도 청양군"),
    @("38090", "경상남도 거제시"),
    @("34370", "충청남도 예산군"),
    @("34360", "충청남도 홍성군"),
    @("35050", "전라북도 남원시"),
    @("32030", "강원특별자치도 강릉시"),
    @("36430", "전라남도 함평군"),
    @("34340", "충청남도 서천군"),
    @("33320", "충청북도 보은군"),
    @("36010", "전라남도 목포시"),
    @("33330", "충청북도 옥천군"),
    @("34310", "충청남도 금산군"),
    @("37400", "경상북도 예천군"),
    @("38080", "경상남도 밀양시"),
    @("37350", "경상북도 영덕군"),
    @("38060", "경상남도 사천시"),
    @("36440", "전라남도 영광군"),
    @("32070", "강원특별자치도 삼척시"),
    @("36420", "전라남도 무안군"),
    @("37340", "경상북도 영양군"),
    @("37310", "대구광역시 군위군"),
    @("36320", "전라남도 곡성군"),
    @("37330", "경상북도 청송군"),
    @("32340", "강원특별자치도 평창군"),
    @("32350", "강원특별자치도 정선군"),
    @("32410", "강원특별자치도 양양군"),
    @("32390", "강원특별자치도 인제군"),
    @("32060", "강원특별자치도 속초시"),
    @("32380", "강원특별자치도 양구군"),
    @("33310", "충청북도 청원군"),
    @("32320", "강원특별자치도 횡성군"),
    @("35011", "전라북도 전주시"),
    @("35310", "전라북도 완주군"),
    @("34320", "충청남도 연기군"),
    @("38020", "경상남도 마산시"),
    @("32040", "강원특별자치도 동해시"),
    @("36310", "전라남도 담양군"),
    @("33390", "충청북도 증평군"),
    @("38040", "경상남도 진해시")
)
# 노선번호 검색어: 숫자 0~9 + 숫자로 시작하지 않는 노선 대비
$keywords = @("0","1","2","3","4","5","6","7","8","9","마을","급행","순환","좌석","직행")

function Get-Tago($url, $file) {
    if (Test-Path $file) { return Get-Content $file -Raw -Encoding UTF8 }   # 이미 받음 → 건너뜀
    for ($try = 1; $try -le 3; $try++) {
        try {
            $script:calls++
            $res = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 60
            $text = [System.Text.Encoding]::UTF8.GetString($res.RawContentStream.ToArray())
            if ($text -match 'LIMITED_NUMBER_OF_SERVICE_REQUESTS|"resultCode"\s*:\s*"?22"?') {
                Write-Host ""
                Write-Host "오늘 TAGO 호출 한도에 도달했습니다 (이번 실행 $($script:calls)건)."
                Write-Host "내일 이 스크립트를 다시 실행하면 이어서 받습니다."
                Pack; exit 0
            }
            if ($text -match 'SERVICE_KEY_IS_NOT_REGISTERED|"resultCode"\s*:\s*"?30"?') { Write-Host "인증키 오류: TAGO 버스노선정보 활용신청을 확인하세요."; exit 1 }
            [System.IO.File]::WriteAllText($file, $text, (New-Object System.Text.UTF8Encoding($false)))
            Start-Sleep -Milliseconds 120
            return $text
        } catch {
            Write-Host "  재시도 $try : $($_.Exception.Message)"
            Start-Sleep -Seconds 2
        }
    }
    return $null
}

function Pack {
    $zip = Join-Path $PSScriptRoot "tago_data.zip"
    if (Test-Path $zip) { Remove-Item $zip }
    Compress-Archive -Path (Join-Path $out "*") -DestinationPath $zip
    Write-Host "지금까지 받은 것: $zip"
}

# 응답에서 노선 ID 뽑기 (item 이 하나면 객체, 여러 개면 배열이라 정규식으로)
function RouteIds($text) {
    if (-not $text) { return @() }
    return [regex]::Matches($text, '"routeid"\s*:\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
}

# 1) 노선 목록
$routes = @{}
foreach ($t in $targets) {
    $code = $t[0]; $name = $t[1]
    $dir = Join-Path $out $code
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $before = ($routes.Keys | Where-Object { $_ -like "$code|*" }).Count
    foreach ($k in $keywords) {
        $kw = [uri]::EscapeDataString($k)
        $text = Get-Tago "$base/getRouteNoList?serviceKey=$key&_type=json&cityCode=$code&routeNo=$kw&numOfRows=1000&pageNo=1" (Join-Path $dir ("routes_" + [System.BitConverter]::ToString([System.Text.Encoding]::UTF8.GetBytes($k)).Replace("-","") + ".json"))
        RouteIds $text | ForEach-Object { $routes["$code|$_"] = 1 }
    }
    $n = ($routes.Keys | Where-Object { $_ -like "$code|*" }).Count
    Write-Host "$name ($code): 노선 $n 개"
}
Write-Host "노선 목록 끝: 전체 $($routes.Count) 개 (이번 실행 호출 $($script:calls)건)"

# 2) 노선별 정류장 순서
$i = 0
foreach ($r in $routes.Keys) {
    $code, $id = $r -split '\|', 2
    $i++
    Get-Tago "$base/getRouteAcctoThrghSttnList?serviceKey=$key&_type=json&cityCode=$code&routeId=$id&numOfRows=500&pageNo=1" (Join-Path (Join-Path $out $code) "stops_$id.json") | Out-Null
    if ($i % 200 -eq 0) { Write-Host "  정류장 순서 $i / $($routes.Count) (이번 실행 호출 $($script:calls)건)" }
}

# 3) 노선 정보 (첫차·막차·배차)
$i = 0
foreach ($r in $routes.Keys) {
    $code, $id = $r -split '\|', 2
    $i++
    Get-Tago "$base/getRouteInfoIem?serviceKey=$key&_type=json&cityCode=$code&routeId=$id" (Join-Path (Join-Path $out $code) "info_$id.json") | Out-Null
    if ($i % 200 -eq 0) { Write-Host "  노선 정보 $i / $($routes.Count) (이번 실행 호출 $($script:calls)건)" }
}

Write-Host ""
Write-Host "모두 받았습니다: 노선 $($routes.Count) 개 (이번 실행 호출 $($script:calls)건)"
Pack
