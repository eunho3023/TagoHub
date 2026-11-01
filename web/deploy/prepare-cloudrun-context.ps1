[CmdletBinding()]
param(
    [string]$ContextDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$webRoot = Join-Path $repoRoot 'web'
$assetsRoot = Join-Path $repoRoot 'app\src\main\assets'
$workRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '.work\transport-migration'))

if ([string]::IsNullOrWhiteSpace($ContextDirectory)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $ContextDirectory = Join-Path $workRoot "cloudrun-context-$stamp"
} elseif (-not [System.IO.Path]::IsPathRooted($ContextDirectory)) {
    $ContextDirectory = Join-Path $repoRoot $ContextDirectory
}
$context = [System.IO.Path]::GetFullPath($ContextDirectory)
$allowedPrefix = $workRoot + [System.IO.Path]::DirectorySeparatorChar
if (-not $context.StartsWith($allowedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The build context must be inside $workRoot"
}

$requiredFiles = @(
    (Join-Path $webRoot 'server.py'),
    (Join-Path $webRoot 'deploy\Dockerfile'),
    (Join-Path $assetsRoot 'subway_stations.tsv'),
    (Join-Path $assetsRoot 'train_timetable.tsv')
)
foreach ($file in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Required deploy input is missing: $file"
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $webRoot 'static') -PathType Container)) {
    throw 'The web/static directory is missing.'
}
if (Test-Path -LiteralPath $context) {
    throw "Refusing to overwrite an existing build context: $context"
}

New-Item -ItemType Directory -Path $context | Out-Null
New-Item -ItemType Directory -Path (Join-Path $context 'static') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $context 'data') | Out-Null
Copy-Item -LiteralPath (Join-Path $webRoot 'server.py') -Destination (Join-Path $context 'server.py')
Copy-Item -Path (Join-Path $webRoot 'static\*') -Destination (Join-Path $context 'static') -Recurse
Copy-Item -LiteralPath (Join-Path $assetsRoot 'subway_stations.tsv') -Destination (Join-Path $context 'data\subway_stations.tsv')
Copy-Item -LiteralPath (Join-Path $assetsRoot 'train_timetable.tsv') -Destination (Join-Path $context 'data\train_timetable.tsv')
Copy-Item -LiteralPath (Join-Path $webRoot 'deploy\Dockerfile') -Destination (Join-Path $context 'Dockerfile')

$files = @(Get-ChildItem -LiteralPath $context -File -Recurse)
$forbidden = @($files | Where-Object {
    $_.Name -in @('local.properties', 'google-services.json', 'GoogleService-Info.plist', '.env') -or
    $_.Extension -in @('.jks', '.keystore', '.p12', '.pfx')
})
if ($forbidden.Count -gt 0) {
    throw "Sensitive file unexpectedly entered the Cloud Run context: $($forbidden[0].Name)"
}

Write-Output "Cloud Run context prepared: $context"
Write-Output "Files included: $($files.Count)"
Write-Output 'API keys, Firebase client configuration, signing files, and local.properties were not copied.'
Write-Output 'Review the staged files and billing/security requirements before any deployment.'
