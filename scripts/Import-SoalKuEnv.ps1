# Run explicitly in the application shell; Maven tests never import .env.
param([string]$Path = (Join-Path $PSScriptRoot '../.env'))
$ErrorActionPreference = 'Stop'
$allowed = @('DB_URL','DB_USERNAME','DB_PASSWORD','JWT_SECRET','JWT_ISSUER','JWT_TTL',
    'MATERIAL_STORAGE_ROOT','GEMINI_API_KEY','GEMINI_MODEL','GEMINI_TIMEOUT','GEMINI_QUESTIONS_PER_SECTION')
foreach ($line in (Get-Content -LiteralPath $Path)) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
    $pair = $line.Split(@('='), 2)
    if ($pair.Length -ne 2 -or $pair[0] -notin $allowed) {
        throw 'Invalid configuration entry; use only keys from .env.example.'
    }
    [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process')
}
Write-Host 'SoalKu local configuration loaded into this shell; values hidden.'
