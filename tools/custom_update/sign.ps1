param([Parameter(Mandatory)][string]$ConfigPath, [Parameter(Mandatory)][string]$InputApk, [Parameter(Mandatory)][string]$OutputApk)
$ErrorActionPreference = 'Stop'
$cfg = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$env:JAVA_HOME = $cfg.javaHome
$secure = Import-Clixml -LiteralPath $cfg.signingPasswordFile
$env:KOMOREBI_SIGN_PASSWORD = ([pscredential]::new('signing', $secure)).GetNetworkCredential().Password
try {
    & (Join-Path $cfg.buildTools 'apksigner.bat') sign --ks $cfg.signingKey --ks-key-alias $cfg.signingAlias --ks-pass env:KOMOREBI_SIGN_PASSWORD --key-pass env:KOMOREBI_SIGN_PASSWORD --out $OutputApk $InputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK署名に失敗しました' }
} finally { Remove-Item Env:\KOMOREBI_SIGN_PASSWORD -ErrorAction SilentlyContinue }
$cert = & (Join-Path $cfg.buildTools 'apksigner.bat') verify --print-certs $OutputApk
if ($LASTEXITCODE -ne 0 -or -not ($cert -match [regex]::Escape($cfg.certificateSha256))) { throw '継続用の署名と一致しません' }
& (Join-Path $cfg.buildTools 'zipalign.exe') -c -P 16 4 $OutputApk
if ($LASTEXITCODE -ne 0) { throw 'APK配置の検証に失敗しました' }
Write-Output '署名とAPK配置の検証が完了しました'
