# Merge GeckoView's required manifest entries into app/AndroidManifest.xml (idempotent).
$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$mf   = Join-Path $proj 'app\AndroidManifest.xml'
$aarmf= Join-Path $proj '.geckoview\geckoview-AndroidManifest.xml'
if (-not (Test-Path $aarmf)) { throw "missing $aarmf (run tools/fetch-geckoview.ps1)" }

$mine = [IO.File]::ReadAllText($mf, [Text.Encoding]::UTF8)
if ($mine -match 'org\.mozilla\.gecko\.process\.GeckoChildProcessServices') {
    Write-Host 'already merged, skip'; exit 0
}
$gv = [IO.File]::ReadAllText($aarmf, [Text.Encoding]::UTF8)
$app = 'com.dsh.desk'

# 1) application attributes from GeckoView (zygote preload)
$appOpenGv = [regex]::Match($gv, '(?s)<application([^>]*)>').Groups[1].Value
$zygote = ''
$zm = [regex]::Match($appOpenGv, 'android:zygotePreloadName="([^"]+)"')
if ($zm.Success) { $zygote = $zm.Groups[1].Value }
Write-Host "zygotePreloadName = $zygote"

# 2) services / provider blocks
$blocks = @()
foreach ($m in [regex]::Matches($gv, '(?s)<service\s+android:name="(org\.mozilla\.[^"]*)"[^>]*>(.*?)</service>')) {
    $body = $m.Groups[0].Value -replace '\$\{applicationId\}', $app
    $blocks += $body.Trim()
}
foreach ($m in [regex]::Matches($gv, '(?s)<provider\s+android:name="(org\.mozilla\.[^"]*)"[^>]*>.*?</provider>')) {
    $blocks += ($m.Groups[0].Value -replace '\$\{applicationId\}', $app).Trim()
}
Write-Host "blocks = $($blocks.Count)"

# 3) permissions + feature
$permBlock = @(
    '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />',
    '    <uses-feature android:glEsVersion="0x00020000" android:required="true" />'
) -join "`r`n"

# 4) insert into mine
$mine = $mine.Replace('    <uses-permission android:name="com.termux.permission.RUN_COMMAND" />',
                      "    <uses-permission android:name=`"com.termux.permission.RUN_COMMAND`" />`r`n$permBlock")
$mine = $mine.Replace('        android:usesCleartextTraffic="true"',
                      "        android:usesCleartextTraffic=`"true`"`r`n        android:extractNativeLibs=`"true`"`r`n        android:zygotePreloadName=`"$zygote`"")

$indented = ($blocks | ForEach-Object {
    ($_ -split "`r?`n" | ForEach-Object { if ($_.Trim()) { "        " + $_.Trim() } else { '' } }) -join "`r`n"
}) -join "`r`n`r`n"
$mine = $mine.Replace('    </application>', "$indented`r`n    </application>")

[IO.File]::WriteAllText($mf, $mine, (New-Object Text.UTF8Encoding($false)))
Write-Host "merged -> $mf"
