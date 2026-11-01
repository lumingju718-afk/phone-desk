$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root       = 'E:\android-toolchain'
$jdk        = Join-Path $root 'jdk'
$bt         = Join-Path $root 'build-tools'
$androidJar = Join-Path $root 'android-sdk\platforms\android-34\android.jar'

$proj  = if ($PSScriptRoot) { $PSScriptRoot } else { 'E:\work\phone-desk' }
$app   = Join-Path $proj 'app'
$build = Join-Path $proj 'build'
$dist  = Join-Path $proj 'dist'

# ---------- signing config (signing.properties is git-ignored) ----------
$signingFile = Join-Path $proj 'signing.properties'
$ksRel   = 'keystore/debug.keystore'
$ksAlias = 'desk'
$ksPass  = $null
$keyPass = $null
if (Test-Path $signingFile) {
    foreach ($line in Get-Content $signingFile -Encoding UTF8) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $k = $t.Substring(0, $i).Trim()
        $v = $t.Substring($i + 1).Trim()
        switch ($k) {
            'keystore'  { $ksRel   = $v }
            'alias'     { $ksAlias = $v }
            'storepass' { $ksPass  = $v }
            'keypass'   { $keyPass = $v }
        }
    }
}
if (-not $ksPass -or -not $keyPass) {
    # first build: generate random passwords and persist them
    $chars = (48..57) + (65..90) + (97..122)
    $rand = -join ($chars | Get-Random -Count 20 | ForEach-Object { [char]$_ })
    $ksPass = $rand
    $keyPass = $rand
    Set-Content -Path $signingFile -Encoding UTF8 -Value @(
        '# signing credentials - git-ignored, do not commit',
        "keystore=$ksRel",
        "alias=$ksAlias",
        "storepass=$ksPass",
        "keypass=$keyPass"
    )
}
$ks = if ([System.IO.Path]::IsPathRooted($ksRel)) { $ksRel } else { Join-Path $proj $ksRel }

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$bt;$env:PATH"

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Run($exe, [string[]]$argv, $what) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $exe @argv 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    $out | ForEach-Object { Write-Host "$_" }
    if ($code -ne 0) { throw "$what failed (exit $code): $exe $($argv -join ' ')" }
}

foreach ($p in @($jdk, $bt, $androidJar, (Join-Path $app 'AndroidManifest.xml'))) {
    if (-not (Test-Path $p)) { throw "missing dependency: $p" }
}

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, $dist, (Join-Path $proj 'keystore') | Out-Null

$resOut  = Join-Path $build 'res'
$genDir  = Join-Path $build 'gen'
$classes = Join-Path $build 'classes'
$dexDir  = Join-Path $build 'dex'
New-Item -ItemType Directory -Force -Path $resOut, $genDir, $classes, $dexDir | Out-Null

Step 'aapt2 compile'
Run (Join-Path $bt 'aapt2.exe') @('compile', '--dir', "$app\res", '-o', $resOut) 'aapt2 compile'

$flats = Get-ChildItem $resOut -Filter *.flat -Recurse | ForEach-Object { $_.FullName }
if (-not $flats) { throw 'no resources compiled' }

Step 'aapt2 link'
$baseApk = Join-Path $build 'base.apk'
$linkArgs = @('link', '-o', $baseApk, '-I', $androidJar, '--manifest', (Join-Path $app 'AndroidManifest.xml'), '--java', $genDir) + $flats
Run (Join-Path $bt 'aapt2.exe') $linkArgs 'aapt2 link'

Step 'javac'
$sources = @()
$sources += Get-ChildItem (Join-Path $app 'src') -Filter *.java -Recurse | ForEach-Object { $_.FullName }
$sources += Get-ChildItem $genDir -Filter *.java -Recurse | ForEach-Object { $_.FullName }
$javacArgs = @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', $androidJar, '-d', $classes) + $sources
Run (Join-Path $jdk 'bin\javac.exe') $javacArgs 'javac'

Step 'jar + d8'
$classesJar = Join-Path $build 'classes.jar'
Run (Join-Path $jdk 'bin\jar.exe') @('cf', $classesJar, '-C', $classes, '.') 'jar'
Run (Join-Path $bt 'd8.bat') @('--lib', $androidJar, '--min-api', '26', '--output', $dexDir, $classesJar) 'd8'

$dex = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $dex)) { throw 'd8 produced no classes.dex' }

Step 'pack classes.dex'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
$existing = $zip.GetEntry('classes.dex')
if ($existing) { $existing.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex, 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

Step 'zipalign'
$aligned = Join-Path $build 'aligned.apk'
Run (Join-Path $bt 'zipalign.exe') @('-f', '-p', '4', $baseApk, $aligned) 'zipalign'

if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $ks -Parent) | Out-Null
    Run (Join-Path $jdk 'bin\keytool.exe') @('-genkeypair', '-keystore', $ks, '-alias', $ksAlias, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-storepass', $ksPass, '-keypass', $keyPass, '-dname', 'CN=PhoneDesk,OU=DSH,O=DSH,L=CN,ST=CN,C=CN') 'keytool'
    Write-Host 'WARNING: a new signing key was generated; it cannot update an existing installation.' -ForegroundColor Yellow
}

Step 'apksigner'
$outApk = Join-Path $dist 'phone-desk.apk'
if (Test-Path $outApk) { Remove-Item $outApk -Force }
Run (Join-Path $bt 'apksigner.bat') @('sign', '--ks', $ks, '--ks-pass', "pass:$ksPass", '--key-pass', "pass:$keyPass", '--out', $outApk, $aligned) 'apksigner sign'

Run (Join-Path $bt 'apksigner.bat') @('verify', '--print-certs', $outApk) 'apksigner verify'

$size = [math]::Round((Get-Item $outApk).Length / 1KB, 1)
Write-Host "`nOK  $outApk  ($size KB)" -ForegroundColor Green
