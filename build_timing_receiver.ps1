param(
    [Parameter(Mandatory=$true)][string]$DeliveryDirectory,
    [Parameter(Mandatory=$true)][string]$ScratchDirectory,
    [string]$Python = 'C:\Users\Tyler\AppData\Local\Programs\Python\Python313\python.exe',
    [string]$DllRoot = 'C:\Codex Projects\New codex\Simplecontroller-public-release-20261016\source\receiver\ViGEmClient'
)
$ErrorActionPreference = 'Stop'
if ($DeliveryDirectory -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+(?:\\|$))') {
    throw 'DeliveryDirectory must be an absolute new directory.'
}
$delivery = [IO.Path]::GetFullPath($DeliveryDirectory)
if (Test-Path -LiteralPath $delivery) { throw "Refusing to overwrite existing delivery: $delivery" }
if ($ScratchDirectory -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+(?:\\|$))') {
    throw 'ScratchDirectory must be an absolute new directory.'
}
$scratch = [IO.Path]::GetFullPath($ScratchDirectory)
if (Test-Path -LiteralPath $scratch) { throw "Refusing to overwrite existing scratch directory: $scratch" }
if ($scratch.TrimEnd('\','/') -eq $delivery.TrimEnd('\','/')) { throw 'Scratch and delivery must be separate directories.' }
$sourceNames = @('receiver_diagnostics.py','receiver_timing.py','simple_controller_receiver.py',
    'camera_follow.py','face_input_server.py','voice_input_server.py','relative_mouse.py','phone_output_protocol.py')
foreach ($name in $sourceNames) {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $name) -PathType Leaf)) { throw "Missing source: $name" }
}
foreach ($architecture in @('x64','x86')) {
    if (-not (Test-Path -LiteralPath (Join-Path $DllRoot "$architecture\ViGEmClient.dll") -PathType Leaf)) { throw "Missing existing DLL: $architecture" }
}
New-Item -ItemType Directory -Path $delivery | Out-Null
foreach ($folder in @('source','dist')) {
    New-Item -ItemType Directory -Path (Join-Path $delivery $folder) | Out-Null
}
New-Item -ItemType Directory -Path $scratch | Out-Null
foreach ($folder in @('build','spec','cache','temp')) {
    New-Item -ItemType Directory -Path (Join-Path $scratch $folder) | Out-Null
}
$source = Join-Path $delivery 'source'
foreach ($name in $sourceNames) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $source $name) }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'RECEIVER_TIMING_DIAGNOSTICS.md') -Destination (Join-Path $delivery 'README.md')
$saved = @{}
foreach ($key in @('PYINSTALLER_CONFIG_DIR','TEMP','TMP','PYTHONDONTWRITEBYTECODE','SC_RECEIVER_TIMING')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:PYINSTALLER_CONFIG_DIR = Join-Path $scratch 'cache'
    $env:TEMP = Join-Path $scratch 'temp'
    $env:TMP = $env:TEMP
    $env:PYTHONDONTWRITEBYTECODE = '1'
    $env:SC_RECEIVER_TIMING = '0'
    $arguments = @('-B','-m','PyInstaller','--onefile','--console','--noupx',
        '--name','Simple Controller Receiver (Hang Diagnostics)',
        '--distpath',(Join-Path $delivery 'dist'),'--workpath',(Join-Path $scratch 'build'),
        '--specpath',(Join-Path $scratch 'spec'),'--paths',$source)
    foreach ($name in @('simple_controller_receiver','receiver_timing','camera_follow','face_input_server',
            'voice_input_server','relative_mouse','phone_output_protocol')) { $arguments += @('--hidden-import',$name) }
    foreach ($architecture in @('x64','x86')) {
        $arguments += @('--add-binary',((Join-Path $DllRoot "$architecture\ViGEmClient.dll") + ";vgamepad\win\vigem\client\$architecture"))
    }
    $arguments += (Join-Path $source 'receiver_diagnostics.py')
    & $Python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Packaging failed with exit code $LASTEXITCODE. New outputs are retained at $delivery" }
    $exe = Join-Path $delivery 'dist\Simple Controller Receiver (Hang Diagnostics).exe'
    $records = @()
    foreach ($name in $sourceNames) {
        $path = Join-Path $source $name
        $records += @{path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
    }
    foreach ($architecture in @('x64','x86')) {
        $path = Join-Path $DllRoot "$architecture\ViGEmClient.dll"
        $records += @{path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
    }
    $report = @{executable=@{path=$exe;sha256=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash};
        inputs=$records;built_at_utc=[DateTimeOffset]::UtcNow.ToString('o');python=$Python;
        validation='Packaged only; receiver not launched; no real inputs or phone operations.'}
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $delivery 'build-report.json') -Encoding utf8
    Write-Output "Separate diagnostic executable prepared: $exe"
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
}
