# Fetch + extract GeckoView (arm64-v8a) and its dependency jars into <proj>/.geckoview
# Windows counterpart of tools/fetch-geckoview.sh. Idempotent.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$proj   = Split-Path -Parent $PSCommandPath
$out    = Join-Path $proj '.geckoview'
$libs   = Join-Path $out 'libs'
$aars   = Join-Path $out 'aars'
$native = Join-Path $out 'native'
foreach ($d in @($libs, $aars, $native)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

$gvVer  = '159.0.20261102144711'
$r8Ver  = '9.5.29'
$first  = 'https://dl.google.com/dl/android/maven2'
$central= 'https://repo1.maven.org/maven2'
$mozilla= 'https://maven.mozilla.org/maven2'

function IsZip([string]$p) {
    if (-not (Test-Path $p)) { return $false }
    $fs = [IO.File]::OpenRead($p)
    try {
        if ($fs.Length -lt 4) { return $false }
        $b = New-Object byte[] 2
        $null = $fs.Read($b, 0, 2)
        return ($b[0] -eq 0x50 -and $b[1] -eq 0x4B)
    } finally { $fs.Dispose() }
}
function Get-File([string]$url, [string]$dest) {
    if ((Test-Path $dest) -and (IsZip $dest)) { return $true }
    if (Test-Path $dest) { Remove-Item $dest -Force }
    & curl.exe -s -L -m 1800 -o $dest $url 2>$null | Out-Null
    if ((Test-Path $dest) -and (IsZip $dest)) { return $true }
    if (Test-Path $dest) { Remove-Item $dest -Force }
    return $false
}

$deps = @(
    @('org/mozilla/geckoview/geckoview-arm64-v8a','geckoview-arm64-v8a',      $gvVer,    $mozilla, $false),
    @('androidx/annotation/annotation',                'annotation',            '1.11.0',  $first,   $true),
    @('androidx/annotation/annotation-experimental',   'annotation-experimental','1.6.0',  $first,   $false),
    @('androidx/collection/collection',                'collection',            '1.6.0',   $first,   $true),
    @('androidx/core/core',                            'core',                  '1.13.1',  $first,   $false),
    @('androidx/lifecycle/lifecycle-common',           'lifecycle-common',      '2.11.0',  $first,   $true),
    @('androidx/lifecycle/lifecycle-process',          'lifecycle-process',     '2.11.0',  $first,   $false),
    @('com/google/android/gms/play-services-fido',     'play-services-fido',    '21.3.1',  $first,   $false),
    @('org/yaml/snakeyaml',                            'snakeyaml',             '2.2',     $central, $true),
    @('androidx/media3/media3-common',                 'media3-common',         '1.11.1',  $first,   $false),
    @('androidx/media3/media3-datasource',             'media3-datasource',     '1.11.1',  $first,   $false),
    @('androidx/media3/media3-decoder',                'media3-decoder',        '1.11.1',  $first,   $false),
    @('androidx/media3/media3-exoplayer',              'media3-exoplayer',      '1.11.1',  $first,   $false),
    @('androidx/media3/media3-exoplayer-hls',          'media3-exoplayer-hls',  '1.11.1',  $first,   $false),
    @('org/jetbrains/kotlin/kotlin-stdlib',            'kotlin-stdlib',         '2.4.20',  $central, $true)
)

Write-Host '== GeckoView deps =='
foreach ($d in $deps) {
    $path = $d[0]; $art = $d[1]; $ver = $d[2]; $repo = $d[3]; $isJar = $d[4]
    $base = "$repo/$path/$ver/$art-$ver"
    if ($isJar) {
        if (-not (Get-File "$base.jar" (Join-Path $aars "$art-$ver.jar"))) { throw "jar failed: $art" }
    } elseif (-not (Get-File "$base.aar" (Join-Path $aars "$art-$ver.aar"))) {
        if (-not (Get-File "$base.jar" (Join-Path $aars "$art-$ver.jar"))) { throw "failed: $art" }
    }
    Write-Host "  $art"
}

Write-Host '== R8 / D8 (newer than build-tools d8) =='
$r8Jar = Join-Path $out "r8-$r8Ver.jar"
if (-not (Test-Path $r8Jar) -or (Get-Item $r8Jar).Length -lt 1MB) {
    & curl.exe -s -L -m 900 -o $r8Jar "$first/com/android/tools/r8/$r8Ver/r8-$r8Ver.jar" 2>$null | Out-Null
}
if (-not (Test-Path $r8Jar)) { throw 'r8 download failed' }
Write-Host ("  r8-{0}.jar {1:N1}MB" -f $r8Ver, ((Get-Item $r8Jar).Length/1MB))

Write-Host '== extract =='
Add-Type -AssemblyName System.IO.Compression.FileSystem
Get-ChildItem $libs -File -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem $native -File -ErrorAction SilentlyContinue | Remove-Item -Force
foreach ($f in Get-ChildItem $aars -File) {
    if ($f.Extension -eq '.jar') {
        Copy-Item $f.FullName (Join-Path $libs $f.Name) -Force
        continue
    }
    $zip = $null
    try { $zip = [IO.Compression.ZipFile]::OpenRead($f.FullName) } catch { Write-Host "  skip bad $($f.Name)"; continue }
    try {
        $cj = $zip.GetEntry('classes.jar')
        if ($cj) { [IO.Compression.ZipFileExtensions]::ExtractToFile($cj, (Join-Path $libs ($f.BaseName + '-classes.jar')), $true) }
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

$libList = Get-ChildItem $libs -File
$natList = Get-ChildItem $native -File
Write-Host ("done: {0} jars ({1:N0}MB), {2} .so ({3:N0}MB), omni.ja {4:N0}MB" -f `
    $libList.Count, (($libList | Measure-Object Length -Sum).Sum/1MB), `
    $natList.Count, (($natList | Measure-Object Length -Sum).Sum/1MB), `
    ((Get-Item (Join-Path $out 'omni.ja')).Length/1MB))
