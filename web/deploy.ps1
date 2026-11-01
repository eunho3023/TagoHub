# TagoHub 웹 버전을 Google Cloud Run 에 올림 (처음 한 번 설정은 web/DEPLOY.md 참고)
#   .\web\deploy.ps1                      # 프로젝트 tagohub, 서울 리전(asia-northeast3)
#   .\web\deploy.ps1 -Project 내프로젝트 -Region asia-northeast3 -Service tagohub-web
# 키는 local.properties 에서 읽어 임시 파일로 Cloud Run 환경변수에 넣고, 끝나면 그 파일을 지움 (이미지·저장소에는 들어가지 않음)
param(
    [string]$Project = "tagohub",
    [string]$Region = "asia-northeast3",
    [string]$Service = "tagohub-web"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Get-Command gcloud -ErrorAction SilentlyContinue)) {
    throw "gcloud 가 없어요. https://cloud.google.com/sdk/docs/install 에서 설치한 뒤 'gcloud auth login' 을 하세요"
}

# local.properties 에서 서버가 쓰는 키만 뽑음
$wanted = "TAGO_SERVICE_KEY", "KAKAO_REST_KEY", "SEOUL_SUBWAY_KEY", "KAKAO_JS_KEY"
$props = @{}
foreach ($line in Get-Content (Join-Path $root "local.properties") -Encoding UTF8) {
    if ($line -match '^\s*([A-Za-z0-9_]+)\s*=(.*)$' -and $wanted -contains $Matches[1]) {
        $props[$Matches[1]] = ($Matches[2].Trim() -replace '\\(.)', '$1')
    }
}
$missing = $wanted | Where-Object { -not $props[$_] }
if ($missing) { Write-Host "키가 없는 기능은 동작하지 않아요: $($missing -join ', ')" -ForegroundColor Yellow }

# YAML 값은 작은따옴표로 감싸서 특수문자(% + = / :)가 있어도 그대로 전달
$envFile = Join-Path ([IO.Path]::GetTempPath()) ("tagohub-env-" + [guid]::NewGuid().ToString("N") + ".yaml")
try {
    $yaml = foreach ($k in $wanted) { if ($props[$k]) { "${k}: '" + ($props[$k] -replace "'", "''") + "'" } }
    Set-Content -Path $envFile -Value $yaml -Encoding UTF8

    gcloud run deploy $Service `
        --project $Project --region $Region --source . `
        --allow-unauthenticated `
        --env-vars-file $envFile `
        --max-instances 2 --min-instances 0 --concurrency 40 --memory 512Mi --cpu 1 --timeout 60
    if ($LASTEXITCODE -ne 0) { throw "배포에 실패했어요 (위 메시지를 확인하세요)" }
}
finally {
    if (Test-Path $envFile) { Remove-Item $envFile -Force }
}
