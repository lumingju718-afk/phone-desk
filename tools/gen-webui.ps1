# 把 webui/*.html 转成 Java 字符串常量（app/src/com/dsh/desk/WebUiHtml.java）
# 用法：powershell -File tools/gen-webui.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

function ConvertTo-JavaBody([string]$path) {
    $s = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
    $s = $s.Replace('\', '\\').Replace('"', '\"').Replace("`r", '')
    $lines = $s -split "`n"
    $out = New-Object System.Collections.Generic.List[string]
    $first = $true
    foreach ($l in $lines) {
        if ($first) {
            $out.Add('            "' + $l + '\n"')
            $first = $false
        } else {
            $out.Add('            + "' + $l + '\n"')
        }
    }
    return ($out -join "`r`n")
}

$desk = ConvertTo-JavaBody (Join-Path $root 'webui\desk.html')
$login = ConvertTo-JavaBody (Join-Path $root 'webui\login.html')

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('package com.dsh.desk;')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('/** 由 webui/*.html 经 tools/gen-webui.ps1 生成，请勿手改 */')
[void]$sb.AppendLine('public final class WebUiHtml {')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('    public static final String DESK =')
[void]$sb.AppendLine($desk.TrimEnd())
[void]$sb.AppendLine('            ;')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('    public static final String LOGIN =')
[void]$sb.AppendLine($login.TrimEnd())
[void]$sb.AppendLine('            ;')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('    private WebUiHtml() { }')
[void]$sb.AppendLine('}')

$dest = Join-Path $root 'app\src\com\dsh\desk\WebUiHtml.java'
[IO.File]::WriteAllText($dest, $sb.ToString(), (New-Object Text.UTF8Encoding($false)))
Write-Host "generated: $dest"
