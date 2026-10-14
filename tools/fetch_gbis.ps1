# 경기 버스 노선 데이터 받기 (TagoHub 앱 안 데이터용)
# 실행: 프로젝트 폴더에서  powershell -ExecutionPolicy Bypass -File tools\fetch_gbis.ps1
# 결과: tools\gbis_data.zip  ← 이 파일 하나를 보내주세요
# 인증키는 프로젝트의 local.properties (TAGO_SERVICE_KEY) 에서 읽습니다. 이 파일에 키를 적지 않습니다.

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $PSScriptRoot
$props = Join-Path $root "local.properties"
if (-not (Test-Path $props)) { Write-Host "local.properties 를 찾지 못했습니다: $props"; exit 1 }
$line = Get-Content $props -Encoding UTF8 | Where-Object { $_ -match '^\s*TAGO_SERVICE_KEY\s*=' } | Select-Object -First 1
if (-not $line) { Write-Host "local.properties 에 TAGO_SERVICE_KEY 가 없습니다"; exit 1 }
$raw = ($line -split '=', 2)[1].Trim()
# 이미 인코딩된 키(%2F 등)면 그대로, 아니면 인코딩
$key = if ($raw -match '%') { $raw } else { [uri]::EscapeDataString($raw) }

$out = Join-Path $PSScriptRoot "gbis_data"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$base = "https://apis.data.go.kr/6410000"

function Save($url, $file) {
    for ($try = 1; $try -le 3; $try++) {
        try {
            Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing -TimeoutSec 60
            return $true
        } catch {
            Write-Host "  재시도 $try : $($_.Exception.Message)"
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

# 1) 노선 검색 0~9 (번호에 그 숫자가 들어간 노선을 모두 돌려줌 → 합치면 경기 노선 전체)
$ids = @{}
foreach ($k in 0..9) {
    $file = Join-Path $out "keyword$k.json"
    Write-Host "노선 검색 '$k' ..."
    if (-not (Save "$base/busrouteservice/v2/getBusRouteListv2?serviceKey=$key&format=json&keyword=$k" $file)) {
        Write-Host "  실패 - 인터넷 연결이나 인증키를 확인하세요"; exit 1
    }
    $text = Get-Content $file -Raw -Encoding UTF8
    if ($text -notmatch '"resultCode"\s*:\s*0') { Write-Host "  응답 오류: $($text.Substring(0, [Math]::Min(200, $text.Length)))" }
    [regex]::Matches($text, '"routeId"\s*:\s*"?(\d+)') | ForEach-Object { $ids[$_.Groups[1].Value] = 1 }
    Write-Host "  지금까지 노선 $($ids.Count)개"
    Start-Sleep -Milliseconds 300
}

# 2) 기반정보 (노선·노선정류소·노선형상·정류소 파일 주소) — 있으면 파일까지
Write-Host "기반정보 ..."
$infoFile = Join-Path $out "baseinfo.json"
if (Save "$base/baseinfoservice/v2/getBaseInfoItemv2?serviceKey=$key&format=json" $infoFile) {
    $info = Get-Content $infoFile -Raw -Encoding UTF8
    foreach ($name in "routeDownloadUrl", "routeStationDownloadUrl", "routeLineDownloadUrl", "stationDownloadUrl") {
        $m = [regex]::Match($info, '"' + $name + '"\s*:\s*"([^"]+)"')
        if ($m.Success) {
            $u = $m.Groups[1].Value -replace '\\/', '/'
            Write-Host "  $name 받는 중 ..."
            if (-not (Save $u (Join-Path $out "$name.txt"))) { Write-Host "  $name 실패 (건너뜀)" }
        }
    }
} else { Write-Host "  기반정보 실패 (노선 검색 결과만으로도 됩니다)" }

# 3) 한 파일로
$zip = Join-Path $PSScriptRoot "gbis_data.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path (Join-Path $out "*") -DestinationPath $zip
Write-Host ""
Write-Host "완료: 경기 노선 $($ids.Count)개"
Write-Host "이 파일을 보내주세요: $zip"
