# 타볼까 국내 중계를 창 없이 띄움 (install-relay.ps1 이 로그인할 때마다 부르게 등록함)
$root = Split-Path -Parent $PSScriptRoot | Split-Path -Parent
$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { $node = 'C:\Program Files\nodejs\node.exe' }
Start-Process -FilePath $node -ArgumentList "`"$PSScriptRoot\relay.mjs`"" -WorkingDirectory $root -WindowStyle Hidden
