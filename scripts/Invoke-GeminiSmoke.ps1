param([switch]$ConfirmPaidCall)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmPaidCall) {
    throw 'Manual only. Use -ConfirmPaidCall to explicitly allow this live Gemini smoke test.'
}
if ([string]::IsNullOrWhiteSpace($env:SOALKU_SMOKE_TOKEN) -or
    $env:SOALKU_SMOKE_MATERIAL_ID -notmatch '^[1-9][0-9]*$') {
    throw 'Set SOALKU_SMOKE_TOKEN and SOALKU_SMOKE_MATERIAL_ID for a local fixture material you own.'
}
$smokeBase = if ($env:SOALKU_BASE_URL) { $env:SOALKU_BASE_URL } else { 'http://127.0.0.1:8080' }
$smokeUri = [Uri]$smokeBase
if ($smokeUri.Host -notin @('127.0.0.1', 'localhost', '::1') -or
    $smokeUri.Scheme -notin @('http', 'https') -or $smokeUri.UserInfo -or $smokeUri.Query -or $smokeUri.Fragment) {
    throw 'This manual smoke script accepts only a local SoalKu server URL.'
}
$smokeUrl = $smokeBase.TrimEnd('/') + '/api/materi/' + $env:SOALKU_SMOKE_MATERIAL_ID + '/generate'
$smokeHeaders = @{ Authorization = 'Bearer ' + $env:SOALKU_SMOKE_TOKEN }
$smokeResult = Invoke-RestMethod -Method Post -Uri $smokeUrl -Headers $smokeHeaders -TimeoutSec 90 -MaximumRedirection 0
if (-not $smokeResult.selesai -or $null -eq $smokeResult.bagian -or @($smokeResult.bagian).Count -eq 0) {
    throw 'Generation did not complete; inspect the local application diagnostics without logging credentials/content.'
}
foreach ($part in $smokeResult.bagian) {
    if ([string]::IsNullOrWhiteSpace($part.ringkasan) -or $null -eq $part.soalIds -or @($part.soalIds).Count -eq 0) {
        throw 'A completed section is missing summary/question references.'
    }
}
Write-Output ('PASS: completed sections = ' + @($smokeResult.bagian).Count + '. Content and credentials omitted.')
