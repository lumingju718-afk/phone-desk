$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$out    = 'E:\work\geckoview'
$libs   = Join-Path $out 'libs'
$aars   = Join-Path $out 'aars'
$native = Join-Path $out 'native'
foreach ($d in @($libs, $native)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
Get-ChildItem $libs -File -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem $native -File -ErrorAction SilentlyContinue | Remove-Item -Force

foreach ($f in Get-ChildItem $aars -File) {
    if ($f.Extension -eq '.jar') {
        Copy-Item $f.FullName (Join-Path $libs $f.Name) -Force
        Write-Host ("  jar {0}" -f $f.Name)
        continue
    }
    $zip = $null
    try { $zip = [IO.Compression.ZipFile]::OpenRead($f.FullName) }
    catch { Write-Host ("  SKIP bad {0}" -f $f.Name); continue }
    try {
        $cj = $zip.GetEntry('classes.jar')
        if ($cj) {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($cj, (Join-Path $libs ($f.BaseName + '-classes.jar')), $true)
            Write-Host ("  aar {0}" -f $f.Name)
        }
        if ($f.BaseName -like 'geckoview-*') {
            foreach ($e in $zip.Entries) {
                if ($e.FullName -like 'jni/arm64-v8a/*.so') {
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $native (Split-Path $e.FullName -Leaf)), $true)
                }
            }
            $om = $zip.GetEntry('assets/omni.ja')
            if ($om) { [IO.Compression.ZipFileExtensions]::ExtractToFile($om, (Join-Path $out 'omni.ja'), $true) }
            $am = $zip.GetEntry('AndroidManifest.xml')
            if ($am) { [IO.Compression.ZipFileExtensions]::ExtractToFile($am, (Join-Path $out 'geckoview-AndroidManifest.xml'), $true) }
        }
    } finally { $zip.Dispose() }
}

Write-Host ''
Write-Host '== libs =='
$libList = Get-ChildItem $libs -File
foreach ($x in $libList) { Write-Host ("  {0,-46} {1,7:N0} KB" -f $x.Name, ($x.Length/1KB)) }
Write-Host '== native =='
$natList = Get-ChildItem $native -File
foreach ($x in $natList) { Write-Host ("  {0,-26} {1,7:N1} MB" -f $x.Name, ($x.Length/1MB)) }
$omni = Join-Path $out 'omni.ja'
$omLen = 0
if (Test-Path $omni) { $omLen = (Get-Item $omni).Length; Write-Host ("  omni.ja {0:N1} MB" -f ($omLen/1MB)) }
$libSum = ($libList | Measure-Object Length -Sum).Sum
$natSum = ($natList | Measure-Object Length -Sum).Sum
Write-Host ''
Write-Host ("TOTAL libs {0:N0}MB native {1:N0}MB" -f ($libSum/1MB), ($natSum/1MB))
