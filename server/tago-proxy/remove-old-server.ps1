# 예전 서버(Cloudflare Workers "tagohub-proxy", https://tagohub-proxy.<계정>.workers.dev)를 Cloudflare 에서 지움. 한 번만 실행.
# 2026-11-22 부터 앱·웹 모두 타볼까(tabolkka.pages.dev)만 씀. 지우면 되돌릴 수 없고, 예전 주소를 쓰는 아주 옛날 앱은 실시간이 안 됨.
# wrangler 가 정말 지울지 한 번 더 물어봄 (y 를 눌러야 지움). 데이터베이스(D1 tagohub-log)는 타볼까가 쓰므로 지우지 않음
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot
if (-not $env:CLOUDFLARE_API_TOKEN) {
    $sec = Read-Host 'Cloudflare API 토큰을 붙여 넣고 Enter (화면에는 안 보여요)' -AsSecureString
    $env:CLOUDFLARE_API_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}
& npx --yes wrangler@4.45.0 delete --name tagohub-proxy
