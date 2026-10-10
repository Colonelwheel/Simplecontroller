param(
    [Parameter(Mandatory=$true)][string]$DeliveryDirectory,
    [string]$Python = 'C:\Users\Tyler\AppData\Local\Programs\Python\Python313\python.exe'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = if (Test-Path -LiteralPath (Join-Path $projectRoot 'staging')) {
    Join-Path $projectRoot 'staging'
} else { $projectRoot }
$deliveryRoot = [System.IO.Path]::GetFullPath($DeliveryDirectory)
if ($DeliveryDirectory -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+(?:\\|$))') {
    throw 'DeliveryDirectory must be an absolute new directory.'
}
if (Test-Path -LiteralPath $deliveryRoot) {
    throw "Refusing to overwrite any existing delivery: $deliveryRoot"
}
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "Existing Python not found: $Python. This script installs nothing."
}
$dllRoot = 'C:\Codex Projects\New codex\Simplecontroller-play-release\Edit exe Folder\ViGEmClient'
$sourceNames = @('simple_controller_receiver.py','camera_follow.py','face_input_server.py','voice_input_server.py')
foreach ($name in $sourceNames) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $name) -PathType Leaf)) {
        throw "Receiver companion missing: $name"
    }
}
foreach ($architecture in @('x64','x86')) {
    if (-not (Test-Path -LiteralPath (Join-Path $dllRoot "$architecture\ViGEmClient.dll") -PathType Leaf)) {
        throw "Existing ViGEmClient DLL missing: $architecture"
    }
}
# All outputs are new. No original spec, build, dist, binary or shortcut is touched.
New-Item -ItemType Directory -Path $deliveryRoot | Out-Null
foreach ($name in @('source','build','dist','spec','cache','temp')) {
    New-Item -ItemType Directory -Path (Join-Path $deliveryRoot $name) | Out-Null
}
$copiedSource = Join-Path $deliveryRoot 'source'
foreach ($name in $sourceNames) {
    Copy-Item -LiteralPath (Join-Path $sourceRoot $name) -Destination (Join-Path $copiedSource $name)
}
$savedEnvironment = @{}
foreach ($key in @('PYINSTALLER_CONFIG_DIR','TMP','TEMP','PYTHONDONTWRITEBYTECODE')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:PYINSTALLER_CONFIG_DIR = Join-Path $deliveryRoot 'cache'
    $env:TMP = Join-Path $deliveryRoot 'temp'
    $env:TEMP = $env:TMP
    $env:PYTHONDONTWRITEBYTECODE = '1'
    $arguments = @('-B','-m','PyInstaller','--onefile','--console','--noupx',
        '--name','Simple Controller Receiver (VoiceAttack and Facial Input)',
        '--distpath',(Join-Path $deliveryRoot 'dist'),
        '--workpath',(Join-Path $deliveryRoot 'build'),
        '--specpath',(Join-Path $deliveryRoot 'spec'),
        '--paths',$copiedSource,
        '--hidden-import','voice_input_server','--hidden-import','face_input_server',
        '--hidden-import','camera_follow',
        '--add-binary',((Join-Path $dllRoot 'x64\ViGEmClient.dll') + ';vgamepad\win\vigem\client\x64'),
        '--add-binary',((Join-Path $dllRoot 'x86\ViGEmClient.dll') + ';vgamepad\win\vigem\client\x86'),
        (Join-Path $copiedSource 'simple_controller_receiver.py'))
    & $Python @arguments
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed: $LASTEXITCODE. New outputs retained at $deliveryRoot" }
    $executablePath = Join-Path $deliveryRoot 'dist\Simple Controller Receiver (VoiceAttack and Facial Input).exe'
    $inputRecords = foreach ($name in $sourceNames) {
        $inputPath = Join-Path $copiedSource $name
        @{ path=$inputPath; sha256=(Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash }
    }
    $record = @{
        executable=@{path=$executablePath;sha256=(Get-FileHash -LiteralPath $executablePath -Algorithm SHA256).Hash}
        inputs=@($inputRecords); built_at_utc=[DateTime]::UtcNow.ToString('o'); python=$Python
        validation='Packaged only. EXE not launched. No real controller, camera or game testing.'
    }
    $reportPath = Join-Path $deliveryRoot 'build-report.json'
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Output "New EXE: $executablePath"
    Write-Output 'Close the old receiver yourself before starting the new one. Existing shortcut is unchanged.'
} finally {
    foreach ($key in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process')
    }
}
