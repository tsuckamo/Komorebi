param([Parameter(Mandatory)][string]$ConfigPath, [switch]$CheckOnly, [switch]$Force)
$ErrorActionPreference = 'Stop'
$cfg = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$runArgs = @((Join-Path $PSScriptRoot 'updater.py'), '--config', $ConfigPath)
if ($CheckOnly) { $runArgs += '--check-only' }
if ($Force) { $runArgs += '--force' }
& $cfg.python @runArgs
$result = $LASTEXITCODE
$statusPath = Join-Path (Split-Path -Parent $ConfigPath) 'status.json'
if (Test-Path -LiteralPath $statusPath) {
    $state = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
    $marker = Join-Path (Split-Path -Parent $ConfigPath) 'last-notification.txt'
    $notificationKey = "$($state.state):$($state.upstreamTag)"
    if ($state.state -in @('failed', 'blocked')) {
        $previous = if (Test-Path -LiteralPath $marker) { Get-Content -LiteralPath $marker -Raw } else { '' }
        if ($previous.Trim() -ne $notificationKey) {
            # 通知には接続先・ファイルパス・例外の詳細を含めない。
            try { (New-Object -ComObject WScript.Shell).Popup('Komorebiの自動更新を停止しました。現在のテレビの版はそのまま使えます。更新ログの確認を依頼してください。', 12, 'Komorebi 更新のお知らせ', 48) | Out-Null } catch {}
            Set-Content -LiteralPath $marker -Value $notificationKey -Encoding utf8
        }
    } elseif (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker }
}
exit $result
